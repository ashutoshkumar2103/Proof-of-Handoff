package com.handoffly.notification;

import com.handoffly.common.config.HandOfflyProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Proof-of-Handoff email is worded by a reusable, editable template. */
class NotificationServiceTest {

    private static final byte[] PDF = "%PDF-1.7 test".getBytes(StandardCharsets.UTF_8);

    private final List<EmailMessage> sent = new ArrayList<>();
    private final HandOfflyProperties properties = new HandOfflyProperties();
    private final NotificationService service = new NotificationService(sent::add, properties);

    private EmailMessage sendPdf(String recipient, String title) {
        service.sendHandoffPdf("rahul@example.test", recipient, "Priya Nair", "HO-3", title,
                "HandOffly-HO-3-Proof-of-Handoff.pdf", PDF);
        return sent.getLast();
    }

    private NotificationService serviceUsing(Path templateFile) {
        HandOfflyProperties custom = new HandOfflyProperties();
        custom.getMail().setPdfTemplateFile(templateFile.toString());
        return new NotificationService(sent::add, custom);
    }

    @Test
    void theBuiltInTemplateProducesTheAgreedEmail() {
        EmailMessage mail = sendPdf("Rahul Sharma", "Kitchen Utensils");

        assertThat(mail.to()).isEqualTo("rahul@example.test");
        assertThat(mail.subject()).isEqualTo("Proof of Handoff — HO-3");
        assertThat(mail.textBody()).isEqualTo("""
                Hello Rahul Sharma,

                Attached is the Proof-of-Handoff record for HO-3.
                Handoff: Kitchen Utensils
                Attachment: HandOffly-HO-3-Proof-of-Handoff.pdf

                Thank You,
                Priya Nair
                """);
        assertThat(mail.attachments()).hasSize(1);
        assertThat(mail.attachments().getFirst().filename()).isEqualTo("HandOffly-HO-3-Proof-of-Handoff.pdf");
        assertThat(mail.attachments().getFirst().contentType()).isEqualTo("application/pdf");
        assertThat(mail.attachments().getFirst().content()).isEqualTo(PDF);
    }

    @Test
    void aBlankRecipientNameFallsBackToThere() {
        assertThat(sendPdf("  ", "Kitchen Utensils").textBody()).startsWith("Hello there,\n");
    }

    @Test
    void aTemplateFileCanReplaceTheWordingAndIsReReadOnEverySend(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("my-template.txt");
        // Windows line endings and the "quotation" placeholder names must both work.
        Files.writeString(file, "Subject: Record {quotationCode}\r\n\r\nDear {recipientName},\r\n"
                + "{quotationTitle} from {senderName} — {attachmentName} {notAPlaceholderWeKnow}\r\n", StandardCharsets.UTF_8);
        NotificationService custom = serviceUsing(file);

        custom.sendHandoffPdf("a@example.test", "Rahul Sharma", "Priya Nair", "HO-3", "Kitchen Utensils", "f.pdf", PDF);
        assertThat(sent.getLast().subject()).isEqualTo("Record HO-3");
        assertThat(sent.getLast().textBody()).isEqualTo(
                "Dear Rahul Sharma,\nKitchen Utensils from Priya Nair — f.pdf {notAPlaceholderWeKnow}\n");

        // Edit the file: the very next email uses the new wording, no restart.
        Files.writeString(file, "Subject: Updated\n\nNew text for {handoffCode}\n", StandardCharsets.UTF_8);
        custom.sendHandoffPdf("a@example.test", "Rahul Sharma", "Priya Nair", "HO-3", "Kitchen Utensils", "f.pdf", PDF);
        assertThat(sent.getLast().subject()).isEqualTo("Updated");
        assertThat(sent.getLast().textBody()).isEqualTo("New text for HO-3\n");
    }

    @Test
    void aTemplateWithoutASubjectLineGetsTheDefaultSubject(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("no-subject.txt");
        Files.writeString(file, "Just a body for {handoffCode}.\n", StandardCharsets.UTF_8);

        serviceUsing(file).sendHandoffPdf("a@example.test", "R", "S", "HO-3", "T", "f.pdf", PDF);

        assertThat(sent.getLast().subject()).isEqualTo("Proof of Handoff — HO-3");
        assertThat(sent.getLast().textBody()).isEqualTo("Just a body for HO-3.\n");
    }

    @Test
    void aMissingTemplateFileFallsBackToTheBuiltInOne(@TempDir Path dir) {
        serviceUsing(dir.resolve("does-not-exist.txt")).sendHandoffPdf("a@example.test", "Rahul", "Priya", "HO-3", "T", "f.pdf", PDF);

        assertThat(sent.getLast().subject()).isEqualTo("Proof of Handoff — HO-3");
        assertThat(sent.getLast().textBody()).contains("Attached is the Proof-of-Handoff record for HO-3.");
    }

    @Test
    void insertedValuesCannotBreakTheSubjectOrBeSubstitutedAgain(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("echo.txt");
        Files.writeString(file, "Subject: {handoffTitle}\n\nTitle: {handoffTitle}\n", StandardCharsets.UTF_8);
        String hostile = "Hi\r\nBcc: evil@example.test {senderName} $1 \\1";

        serviceUsing(file).sendHandoffPdf("a@example.test", "R", "Priya Nair", "HO-3", hostile, "f.pdf", PDF);

        // One header line: no CR/LF, so no injected Bcc header.
        assertThat(sent.getLast().subject()).doesNotContain("\r").doesNotContain("\n");
        assertThat(sent.getLast().subject()).startsWith("Hi Bcc: evil@example.test");
        // The value is inserted literally: not substituted again, and $/\ are not treated as regex syntax.
        assertThat(sent.getLast().textBody()).contains("{senderName} $1 \\1").doesNotContain("Priya Nair");
    }
}
