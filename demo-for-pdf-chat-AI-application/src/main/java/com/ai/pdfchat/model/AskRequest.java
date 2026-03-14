package com.ai.pdfchat.model;

import lombok.Data;

@Data
public class AskRequest {
    private String question;
    /** Optional: when set, only chunks from this document are used to answer. */
    private String documentId;
}
