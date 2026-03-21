package com.multi.vidulum.bank_data_adapter.domain.exceptions;

public class EmptyFileException extends RuntimeException {

    public EmptyFileException() {
        super("Uploaded file is empty");
    }
}
