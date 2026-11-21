package com.lrrecomp.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The reason a renderer start failed, saved by the native abort handler (android_glue.cpp
 * OnAbort) for the next start's GPU driver page: read once, trimmed, then gone.
 */
public class GraphicsFailureTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savedReasonIsReadOnce() throws IOException {
        File file = new File(folder.getRoot(), GpuDriverStore.GRAPHICS_FAILURE_FILE);
        Files.write(file.toPath(), "Mali-G715: no vertexPipelineStoresAndAtomics\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("Mali-G715: no vertexPipelineStoresAndAtomics", GpuDriverStore.takeGraphicsFailure(file));
        assertFalse(file.exists());
        assertNull(GpuDriverStore.takeGraphicsFailure(file));
    }

    @Test
    public void emptyOrMissingFileIsNoFailure() throws IOException {
        File file = new File(folder.getRoot(), GpuDriverStore.GRAPHICS_FAILURE_FILE);
        assertNull(GpuDriverStore.takeGraphicsFailure(file));
        Files.write(file.toPath(), new byte[0]);
        assertNull(GpuDriverStore.takeGraphicsFailure(file));
        assertFalse(file.exists());
    }
}
