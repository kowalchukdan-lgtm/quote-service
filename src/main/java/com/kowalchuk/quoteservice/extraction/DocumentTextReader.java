package com.kowalchuk.quoteservice.extraction;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Turns a PDF into plain text for the model. Digital PDFs only: a scan has no text layer and is
 * rejected rather than sent as an empty prompt. OCR (for example mistral-document-ai) is a later
 * step and would plug in here.
 * <p>
 * The limits keep one upload from becoming an expensive or slow model call.
 */
public class DocumentTextReader {

	static final int MAX_PAGES = 20;

	static final int MAX_CHARACTERS = 60_000;

	// Fewer meaningful characters than this means there is no usable text layer.
	private static final int MIN_CHARACTERS = 20;

	public String read(byte[] pdf) {
		if (pdf.length == 0) {
			throw new UnreadableDocumentException("The document is empty.");
		}
		try (PDDocument document = Loader.loadPDF(pdf)) {
			int pages = document.getNumberOfPages();
			if (pages > MAX_PAGES) {
				throw new UnreadableDocumentException(
						"The document has " + pages + " pages; the limit is " + MAX_PAGES + ".");
			}
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			String text = stripper.getText(document).strip();
			if (text.replaceAll("\\s+", "").length() < MIN_CHARACTERS) {
				throw new UnreadableDocumentException(
						"The document has no text layer; it may be a scan, which needs OCR.");
			}
			if (text.length() > MAX_CHARACTERS) {
				throw new UnreadableDocumentException("The document has " + text.length()
						+ " characters of text; the limit is " + MAX_CHARACTERS + ".");
			}
			return text;
		}
		catch (InvalidPasswordException ex) {
			throw new UnreadableDocumentException("The document is password-protected.", ex);
		}
		catch (IOException ex) {
			throw new UnreadableDocumentException("The document is not a readable PDF.", ex);
		}
	}

}
