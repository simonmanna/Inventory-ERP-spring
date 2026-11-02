# ADR-S046: DMS full-text search and OCR without new libraries

- Status: Accepted (2026-11-02)
- Related: [ADR-S044](ADR-S044-document-management.md)

## Context

Document search must find words inside files (contracts, scanned letters, receipts). The usual Java stack is Apache
Tika + Tess4J; Tika's parser bundle adds tens of megabytes and dozens of transitive dependencies, Tess4J needs native
libraries. AGENTS.md §3 asks for an ADR before any framework outside the pinned list.

## Decision

1. **Extract with what is already pinned**: plain text / CSV / JSON / XML as UTF-8, PDF text layer with OpenPDF
   (`PdfTextExtractor`), Word `.docx` and Excel `.xlsx` with Apache POI. Other types are `skipped`.
2. **OCR through the `tesseract` command line**, only when `DMS_OCR_ENABLED=true` and the binary is on `PATH`
   (languages `DMS_OCR_LANGUAGES`, default `eng`; e.g. `eng+ara`). Scanned PDFs (no text layer) and images
   (PNG, JPEG, TIFF, BMP, WEBP) are OCR'd with a 60 s timeout. No Java dependency.
3. **Store text on the document** (`contentText`, capped at 1 M characters) with a generated
   `tsvector('simple', name + description + text)` column and a GIN index. Search uses `websearch_to_tsquery`,
   ranks with `ts_rank`, highlights with `ts_headline`, and also matches name / tag substrings.
4. **Never block an upload**: extraction runs after commit on a bounded virtual-thread pool, a ShedLock job sweeps
   anything left `pending`, and errors only mark the document `failed`.

## Consequences

- To enable OCR in Docker, add `tesseract-ocr` (+ language packs) to `infra/docker/Dockerfile.api` and set
  `DMS_OCR_ENABLED=true`.
- `.doc`, `.xls`, `.pptx` and e-mail files are not indexed yet (name and tags still are).
