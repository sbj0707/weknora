import io
import os
import queue
import threading
import unittest
from concurrent import futures
from pathlib import Path
from unittest import mock

import grpc
from PIL import Image

from docreader.auth import AuthInterceptor
from docreader.main import DocReaderServicer
from docreader.page_images import RENDER_PROFILE
from docreader.proto import docreader_pb2 as pb, docreader_pb2_grpc as rpc

PDF = (Path(__file__).resolve().parents[2] / 'internal/pageimage/testdata/pages.pdf').read_bytes()


def upload(content=PDF):
    for start in range(0, len(content), 1 << 20):
        yield pb.RenderPagesRequest(file_chunk=content[start:start + (1 << 20)])
    yield pb.RenderPagesRequest(finish_upload=True)


class PageImagesTest(unittest.TestCase):
    def setUp(self):
        self.server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
        # The dedicated RPC must not depend on parser construction or call OCR.
        servicer = DocReaderServicer.__new__(DocReaderServicer)
        servicer.parser = mock.Mock()
        servicer.parser.parse_file.side_effect = AssertionError('must not parse/OCR')
        rpc.add_DocReaderServicer_to_server(servicer, self.server)
        port = self.server.add_insecure_port('127.0.0.1:0')
        self.server.start()
        self.channel = grpc.insecure_channel(f'127.0.0.1:{port}')
        self.stub = rpc.DocReaderStub(self.channel)

    def tearDown(self):
        self.channel.close()
        self.server.stop(0).wait()

    def test_real_jpeg_pages_and_out_of_order_requests(self):
        def requests():
            yield from upload()
            yield pb.RenderPagesRequest(page_number=2)
            yield pb.RenderPagesRequest(page_number=1)
        frames = list(self.stub.RenderPages(requests(), timeout=10))
        self.assertEqual(frames[0].info.page_count, 2)
        self.assertEqual(frames[0].info.render_profile, RENDER_PROFILE)
        self.assertEqual([f.page.page_number for f in frames[1:]], [2, 1])
        with Image.open(io.BytesIO(frames[1].page.image_data)) as image:
            self.assertEqual(image.size, (500, 250))
            red, _, blue = image.getpixel((10, 10))
            self.assertGreater(blue, red)
        self.assertFalse(frames[1].page.error)

    def test_count_only_closes_without_rendering(self):
        with mock.patch('docreader.page_images._render_page_to_jpeg') as render:
            frames = list(self.stub.RenderPages(upload(), timeout=10))
            self.assertEqual(len(frames), 1)
            render.assert_not_called()

    def test_bad_upload_and_out_of_range_page_are_rejected(self):
        cases = [
            (iter([pb.RenderPagesRequest(finish_upload=True)]), grpc.StatusCode.INVALID_ARGUMENT),
            (iter([pb.RenderPagesRequest(file_chunk=PDF)]), grpc.StatusCode.INVALID_ARGUMENT),
            (upload(b'not a pdf'), grpc.StatusCode.INVALID_ARGUMENT),
            (iter([*upload(), pb.RenderPagesRequest(page_number=3)]), grpc.StatusCode.INVALID_ARGUMENT),
            (iter([pb.RenderPagesRequest(file_chunk=b'x' * ((1 << 20) + 1))]), grpc.StatusCode.INVALID_ARGUMENT),
        ]
        for requests, expected in cases:
            with self.subTest(expected=expected), self.assertRaises(grpc.RpcError) as error:
                list(self.stub.RenderPages(requests, timeout=10))
            self.assertEqual(error.exception.code(), expected)
        with mock.patch('docreader.page_images.MAX_PDF_BYTES', 5):
            with self.assertRaises(grpc.RpcError) as error:
                list(self.stub.RenderPages(upload(), timeout=10))
            self.assertEqual(error.exception.code(), grpc.StatusCode.RESOURCE_EXHAUSTED)

    def test_one_failed_page_does_not_discard_other_pages(self):
        from docreader.page_images import _render_page_to_jpeg
        calls = 0
        def render(*args, **kwargs):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise ValueError('fixture failure')
            return _render_page_to_jpeg(*args, **kwargs)
        with mock.patch('docreader.page_images._render_page_to_jpeg', side_effect=render):
            frames = list(self.stub.RenderPages(iter([*upload(), pb.RenderPagesRequest(page_number=2), pb.RenderPagesRequest(page_number=1)]), timeout=10))
        self.assertTrue(frames[1].page.error)
        self.assertFalse(frames[2].page.error)
        self.assertTrue(frames[2].page.image_data)

    def test_cancel_releases_pdf_handle(self):
        from docreader.page_images import _close_pdfium_resource
        closed = threading.Event()
        requests_queue = queue.Queue()
        def requests():
            yield from upload()
            item = requests_queue.get(timeout=10)
            if item is not None:
                yield item
        def close(resource):
            if resource.__class__.__name__ == 'PdfDocument':
                closed.set()
            return _close_pdfium_resource(resource)
        with mock.patch('docreader.page_images._close_pdfium_resource', side_effect=close):
            call = self.stub.RenderPages(requests(), timeout=10)
            self.assertEqual(next(call).info.page_count, 2)
            call.cancel()
            requests_queue.put(None)
            self.assertTrue(closed.wait(5), 'PDF handle was not released after cancellation')

    def test_authentication_also_guards_bidi_rendering(self):
        with mock.patch.dict(os.environ, {'GRPC_AUTH_TOKEN': 'page-images-test-token'}):
            server = grpc.server(futures.ThreadPoolExecutor(max_workers=2), interceptors=[AuthInterceptor()])
        rpc.add_DocReaderServicer_to_server(DocReaderServicer.__new__(DocReaderServicer), server)
        port = server.add_insecure_port('127.0.0.1:0')
        server.start()
        try:
            with grpc.insecure_channel(f'127.0.0.1:{port}') as channel:
                stub = rpc.DocReaderStub(channel)
                with self.assertRaises(grpc.RpcError) as error:
                    list(stub.RenderPages(upload(), timeout=10))
                self.assertEqual(error.exception.code(), grpc.StatusCode.UNAUTHENTICATED)
                frames = list(stub.RenderPages(upload(), metadata=[('authorization', 'Bearer page-images-test-token')], timeout=10))
                self.assertEqual(frames[0].info.page_count, 2)
        finally:
            server.stop(0).wait()


if __name__ == '__main__':
    unittest.main()
