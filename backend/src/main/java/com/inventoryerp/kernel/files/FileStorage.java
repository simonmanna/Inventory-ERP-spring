package com.inventoryerp.kernel.files;

import java.io.InputStream;

/** Blob storage behind {@code /files} ({@code STORAGE_DRIVER=local|s3}). */
public interface FileStorage {

    String driver();

    void put(String key, byte[] bytes, String contentType);

    InputStream get(String key);

    void delete(String key);
}
