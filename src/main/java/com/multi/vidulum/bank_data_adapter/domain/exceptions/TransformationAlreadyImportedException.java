package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class TransformationAlreadyImportedException extends RuntimeException {

    private final String transformationId;
    private final String stagingSessionId;

    public TransformationAlreadyImportedException(String transformationId, String stagingSessionId) {
        super(String.format("Transformation [%s] has already been imported. Staging session: [%s]",
            transformationId, stagingSessionId));
        this.transformationId = transformationId;
        this.stagingSessionId = stagingSessionId;
    }
}
