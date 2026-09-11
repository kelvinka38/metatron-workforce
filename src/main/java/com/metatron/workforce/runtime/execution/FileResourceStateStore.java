package com.metatron.workforce.runtime.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class FileResourceStateStore implements ResourceStateStore {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    public FileResourceStateStore(Path path){this.path=Objects.requireNonNull(path);}
    @Override public synchronized Snapshot load(){
        if(!Files.exists(path)) return new Snapshot(java.util.Map.of(),java.util.Map.of());
        try{return mapper.readValue(path.toFile(),Snapshot.class);}catch(Exception e){throw new IllegalStateException("cannot load resource state: "+path,e);}
    }
    @Override public synchronized void save(Snapshot snapshot){
        try{
            Path parent=path.toAbsolutePath().getParent(); if(parent!=null)Files.createDirectories(parent);
            Path tmp=path.resolveSibling(path.getFileName()+".tmp"); mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),snapshot);
            try{Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){throw new IllegalStateException("cannot persist resource state: "+path,e);}
    }
}
