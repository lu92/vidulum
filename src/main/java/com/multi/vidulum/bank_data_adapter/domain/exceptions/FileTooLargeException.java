package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class FileTooLargeException extends RuntimeException {

    private final long fileSize;
    private final long maxSize;

    public FileTooLargeException(long fileSize, long maxSize) {
        super(String.format("File size %d bytes exceeds maximum allowed size of %d bytes", fileSize, maxSize));
        this.fileSize = fileSize;
        this.maxSize = maxSize;
    }
}
