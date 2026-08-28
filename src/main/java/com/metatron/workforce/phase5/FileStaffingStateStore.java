package com.metatron.workforce.phase5;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

public final class FileStaffingStateStore implements StaffingStateStore {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    public FileStaffingStateStore(Path path) { this.path = Objects.requireNonNull(path); }
    @Override public synchronized List<StaffingRequest> load() {
        if (!Files.exists(path)) return List.of();
        try { return mapper.readValue(path.toFile(), new TypeReference<List<StaffingRequest>>() {}); }
        catch (IOException e) { throw new IllegalStateException("cannot load staffing state", e); }
    }
    @Override public synchronized void save(List<StaffingRequest> values) {
        try {
            Path parent = path.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName()+".tmp"); mapper.writeValue(tmp.toFile(), values);
            try { Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING); }
        } catch(IOException e){ throw new IllegalStateException("cannot persist staffing state",e); }
    }
}
