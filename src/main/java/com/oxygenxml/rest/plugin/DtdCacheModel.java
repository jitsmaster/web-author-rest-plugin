package com.oxygenxml.rest.plugin;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;

import org.apache.commons.io.IOUtils;

/**
 *
 * @author awang
 */
public class DtdCacheModel extends DtdModel {

    public byte[] Content;

    public DtdCacheModel(File file) throws FileNotFoundException, IOException {
        Name = file.getName();

        BasicFileAttributes attr = Files.readAttributes(Paths.get(file.getAbsolutePath()), BasicFileAttributes.class);
        setLastUpdatedTime(attr.lastModifiedTime());

        System.out.println("Last mod time for " + Name + ":" + getLastUpdatedTime().toString());

        Content = Files.readAllBytes(file.toPath());
    }

    public DtdCacheModel(DtdModelWithContent d) {
        Name = d.Name;
        Id = d.Id;
        LastUpdated = d.LastUpdated;
        Content = d.Content.getBytes(StandardCharsets.UTF_8);
    }

    public DtdCacheModel(String name, InputStream s) throws IOException {
        Name = name;
        setLastUpdatedTime(FileTime.from(Instant.now()));
        Content = IOUtils.toByteArray(s);
    }
}

