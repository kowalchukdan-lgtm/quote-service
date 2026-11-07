package com.kowalchuk.quoteservice.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DocumentTextReaderTest {

	private final DocumentTextReader reader = new DocumentTextReader();

	@Test
	void readsTheTextOfADigitalPdf() {
		String text = reader.read(GoldenSet.bytes("sample-01-keystone-pipe-heating.pdf"));

		assertThat(text).contains("Keystone Pipe & Heating LLC", "2215 Tilghman Street, Suite 4", "238220",
				"$1,850,000", "12/01/2026");
	}

	@Test
	void readsAFreeTextLetter() {
		String text = reader.read(GoldenSet.bytes("sample-02-brightline-code-works.pdf"));

		assertThat(text).contains("Brightline Code Works Inc.", "$4.2 million", "January 1, 2027");
	}

	@Test
	void rejectsSomethingThatIsNotAPdf() {
		assertThatThrownBy(() -> reader.read("hello".getBytes()))
			.isInstanceOf(UnreadableDocumentException.class)
			.hasMessageContaining("not a readable PDF");
	}

	@Test
	void rejectsAnEmptyUpload() {
		assertThatThrownBy(() -> reader.read(new byte[0])).isInstanceOf(UnreadableDocumentException.class)
			.hasMessageContaining("empty");
	}

	@Test
	void rejectsAPdfWithNoTextLayerSuchAsAScan() throws IOException {
		byte[] blank = pdf(1, null);

		assertThatThrownBy(() -> reader.read(blank)).isInstanceOf(UnreadableDocumentException.class)
			.hasMessageContaining("no text layer");
	}

	@Test
	void rejectsTooManyPages() throws IOException {
		byte[] large = pdf(DocumentTextReader.MAX_PAGES + 1, "Page text long enough to count as content.");

		assertThatThrownBy(() -> reader.read(large)).isInstanceOf(UnreadableDocumentException.class)
			.hasMessageContaining("pages");
	}

	@Test
	void rejectsTooMuchText() throws IOException {
		// 40 lines of 90 characters on each of 20 pages is about 72,000 characters.
		byte[] wordy = pdfWithLines(DocumentTextReader.MAX_PAGES, "x".repeat(90), 40);

		assertThatThrownBy(() -> reader.read(wordy)).isInstanceOf(UnreadableDocumentException.class)
			.hasMessageContaining("characters");
	}

	@Test
	void rejectsAPasswordProtectedPdf() throws IOException {
		byte[] locked;
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
			document.protect(policy);
			locked = save(document);
		}

		assertThatThrownBy(() -> reader.read(locked)).isInstanceOf(UnreadableDocumentException.class)
			.hasMessageContaining("password");
	}

	private static byte[] pdf(int pages, @Nullable String text) throws IOException {
		return pdfWithLines(pages, text, 1);
	}

	private static byte[] pdfWithLines(int pages, @Nullable String line, int linesPerPage) throws IOException {
		try (PDDocument document = new PDDocument()) {
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			for (int i = 0; i < pages; i++) {
				PDPage page = new PDPage();
				document.addPage(page);
				if (line != null) {
					try (PDPageContentStream content = new PDPageContentStream(document, page)) {
						content.setFont(font, 8);
						for (int l = 0; l < linesPerPage; l++) {
							content.beginText();
							content.newLineAtOffset(20, 760 - l * 10);
							content.showText(line);
							content.endText();
						}
					}
				}
			}
			return save(document);
		}
	}

	private static byte[] save(PDDocument document) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		document.save(out);
		return out.toByteArray();
	}

}
