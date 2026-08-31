package com.metatron.workforce.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.util.*;

public final class FileExecutionAttemptStore implements ExecutionAttemptStore {
    private final Path path; private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    public FileExecutionAttemptStore(Path path){this.path=Objects.requireNonNull(path);}
    @Override public synchronized Map<String,ExecutionAttempt> load(){
        if(!Files.exists(path)) return Map.of();
        try{return mapper.readValue(path.toFile(),new TypeReference<Map<String,ExecutionAttempt>>(){});}catch(Exception e){throw new IllegalStateException("cannot load execution attempts: "+path,e);}
    }
    @Override public synchronized void save(Map<String,ExecutionAttempt> attempts){
        try{Path parent=path.toAbsolutePath().getParent(); if(parent!=null) Files.createDirectories(parent); Path tmp=path.resolveSibling(path.getFileName()+".tmp"); mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),attempts); try{Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw new IllegalStateException("cannot persist execution attempts: "+path,e);}
    }
}
