package com.handoffly.attachment;

import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Handoff attachments share their upload rules (allowed types, size limit, safe filename) with
 * support-ticket attachments; this keeps the handoff side proven end to end.
 */
class HandoffAttachmentTest extends ApiTestBase {

    private static final byte[] NOTE = "Serial number photo notes".getBytes(StandardCharsets.UTF_8);

    private long newHandoff(Account owner) throws Exception {
        return ((Number) JsonPath.read(createHandoff(owner), "$.id")).longValue();
    }

    @Test
    void anOwnerCanUploadListDownloadAndDeleteAnAttachment() throws Exception {
        Account owner = register();
        long handoffId = newHandoff(owner);
        String base = "/api/v1/handoffs/" + handoffId + "/attachments";

        // A path in the filename is stripped; only the plain name is kept.
        MockMultipartFile file = new MockMultipartFile("file", "../../etc/notes.txt", "text/plain; charset=utf-8", NOTE);
        String created = mvc.perform(as(owner, multipart(base).file(file).param("kind", "EVIDENCE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("notes.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(NOTE.length))
                .andReturn().getResponse().getContentAsString();
        int attachmentId = JsonPath.read(created, "$.id");

        mvc.perform(as(owner, get(base))).andExpect(jsonPath("$.length()").value(1));

        byte[] downloaded = mvc.perform(as(owner, get(base + "/" + attachmentId + "/content")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("notes.txt")))
                .andExpect(content().contentType("text/plain"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(NOTE);

        mvc.perform(as(owner, delete(base + "/" + attachmentId))).andExpect(status().isNoContent());
        mvc.perform(as(owner, get(base))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void disallowedEmptyAndOversizedFilesAreRejected() throws Exception {
        Account owner = register();
        String base = "/api/v1/handoffs/" + newHandoff(owner) + "/attachments";

        mvc.perform(as(owner, multipart(base).file(new MockMultipartFile("file", "virus.exe", "application/x-msdownload", NOTE))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(owner, multipart(base).file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))))
                .andExpect(status().isBadRequest());
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];   // the test profile allows 5 MiB
        mvc.perform(as(owner, multipart(base).file(new MockMultipartFile("file", "big.txt", "text/plain", tooBig))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(owner, multipart(base))).andExpect(status().isBadRequest());   // no file part at all
        mvc.perform(as(owner, get(base))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void otherCustomersCannotTouchSomeoneElsesAttachments() throws Exception {
        Account owner = register();
        Account outsider = register();
        long handoffId = newHandoff(owner);
        String base = "/api/v1/handoffs/" + handoffId + "/attachments";
        int attachmentId = JsonPath.read(mvc.perform(as(owner, multipart(base)
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", NOTE))))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(as(outsider, get(base))).andExpect(status().isForbidden());
        mvc.perform(as(outsider, get(base + "/" + attachmentId + "/content"))).andExpect(status().isForbidden());
        mvc.perform(as(outsider, multipart(base).file(new MockMultipartFile("file", "x.txt", "text/plain", NOTE))))
                .andExpect(status().isForbidden());
        mvc.perform(as(outsider, delete(base + "/" + attachmentId))).andExpect(status().isForbidden());
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
    }

    @Test
    void aCancelledHandoffTakesNoMoreAttachments() throws Exception {
        Account owner = register();
        long handoffId = newHandoff(owner);
        mvc.perform(as(owner, post("/api/v1/handoffs/" + handoffId + "/cancel"))).andExpect(status().isOk());

        mvc.perform(as(owner, multipart("/api/v1/handoffs/" + handoffId + "/attachments")
                        .file(new MockMultipartFile("file", "late.txt", "text/plain", NOTE))))
                .andExpect(status().isConflict());
    }
}
