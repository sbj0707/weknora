"""Isolated PDF page renderer. Called by the app; no network or OCR side effects."""
import json
import sys

import pypdfium2 as pdfium


def main():
    with pdfium.PdfDocument(sys.argv[1]) as document:
        count = len(document)
        if count < 1 or count > 5000:
            raise ValueError("PDF must contain 1-5000 pages")
        if len(sys.argv) == 2:
            print(json.dumps({"pages": count}))
            return
        number = int(sys.argv[2])
        if number < 1 or number > count:
            raise ValueError("Page is outside the document")
        with document[number - 1] as page:
            width, height = page.get_size()
            if min(width, height) <= 0:
                raise ValueError("Invalid page dimensions")
            scale = min(2.5, 2000 / max(width, height))
            with page.render(scale=scale) as bitmap:
                image = bitmap.to_pil().convert("RGB")
                image.save(sys.argv[3], format="JPEG", quality=85)
                print(json.dumps({"width": image.width, "height": image.height}))


if __name__ == "__main__":
    main()
