package com.multi.vidulum;

import com.multi.vidulum.common.JsonContent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ContentReader {

    public static JsonContent load(String path) {

        try {
            Resource resource = new ClassPathResource(path);
            File file = resource.getFile();
            String content = new String(Files.readAllBytes(file.toPath()));
            return new JsonContent(content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
