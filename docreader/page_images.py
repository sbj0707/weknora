"""Independent page-image RPC. Reuses PDF parsing's PDFium lock and renderer."""

import io
import logging
import math
import tempfile

import grpc
import pypdfium2 as pdfium
from PIL import Image

from docreader.parser.pdf_parser import (
    _PDFIUM_LOCK,
    _close_pdfium_resource,
    _render_page_to_jpeg,
)
from docreader.proto import docreader_pb2 as pb

logger = logging.getLogger(__name__)
RENDER_PROFILE = "pdf-jpeg-2000-q85-v1"
MAX_PDF_BYTES = 256 << 20
MAX_IMAGE_BYTES = 4 << 20
MAX_CHUNK_BYTES = 1 << 20
MAX_PAGES = 5000


def render_pages(requests, context):
    """One bounded upload, one metadata frame, then one response per request.

    The file and PDF handle belong to this RPC and are released on EOF, errors
    and cancellation. Never hold the global PDFium lock while waiting for the
    app to request another page.
    """
    document = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".pdf") as source:
            size = 0
            for request in requests:
                if not context.is_active():
                    return
                kind = request.WhichOneof("payload")
                if kind == "finish_upload" and request.finish_upload:
                    break
                if kind != "file_chunk" or len(request.file_chunk) > MAX_CHUNK_BYTES:
                    context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Invalid PDF upload frame")
                size += len(request.file_chunk)
                if size > MAX_PDF_BYTES:
                    context.abort(grpc.StatusCode.RESOURCE_EXHAUSTED, "PDF exceeds 256 MiB")
                source.write(request.file_chunk)
            else:
                context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Incomplete PDF upload")
            if not size:
                context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Empty PDF")
            source.flush()
            try:
                with _PDFIUM_LOCK:
                    if not context.is_active():
                        return
                    document = pdfium.PdfDocument(source.name)
                    count = len(document)
            except Exception:
                context.abort(grpc.StatusCode.INVALID_ARGUMENT, "PDF is unreadable or encrypted")
            if not 1 <= count <= MAX_PAGES:
                context.abort(grpc.StatusCode.INVALID_ARGUMENT, "PDF must contain 1-5000 pages")
            yield pb.RenderPagesResponse(info=pb.PDFPageInfo(page_count=count, render_profile=RENDER_PROFILE))
            for request in requests:
                if not context.is_active():
                    return
                number = request.page_number
                if request.WhichOneof("payload") != "page_number" or not 1 <= number <= count:
                    context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Page is outside the document")
                try:
                    with _PDFIUM_LOCK:
                        if not context.is_active():
                            return
                        page = document[number - 1]
                        try:
                            dimensions = page.get_size()
                            if any(not math.isfinite(v) or v <= 0 for v in dimensions):
                                raise ValueError("Invalid page dimensions")
                            data = _render_page_to_jpeg(page, scale=2.5, quality=85, max_edge=2000)
                        finally:
                            _close_pdfium_resource(page)
                    if len(data) > MAX_IMAGE_BYTES:
                        raise ValueError("Page image exceeds 4 MiB")
                    with Image.open(io.BytesIO(data)) as image:
                        width, height = image.size
                    response = pb.RenderedPage(page_number=number, image_data=data, width=width, height=height)
                    logger.info("RenderPages page=%d bytes=%d", number, len(data))
                except Exception:
                    logger.warning("RenderPages failed page=%d", number, exc_info=True)
                    response = pb.RenderedPage(page_number=number, error="PDF page rendering failed")
                if not context.is_active():
                    return
                yield pb.RenderPagesResponse(page=response)
    finally:
        if document is not None:
            with _PDFIUM_LOCK:
                _close_pdfium_resource(document)
