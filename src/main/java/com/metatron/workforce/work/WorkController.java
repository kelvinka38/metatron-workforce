package com.metatron.workforce.work;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/workforce/work")
public class WorkController {
    private final WorkService service;
    public WorkController(WorkService service){this.service=service;}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public InstitutionalWork originate(@RequestBody OriginateCommand c){
        return service.originate(c.workId(),c.objectiveRef(),c.organizationContextId(),c.originatedByWorkerId(),c.description(),Instant.now());
    }
    @PostMapping("/{id}/proposal") public InstitutionalWork proposal(@PathVariable String id,@RequestBody RefCommand c){return service.linkProposal(id,c.ref(),Instant.now());}
    @PostMapping("/{id}/assignment") public InstitutionalWork assignment(@PathVariable String id,@RequestBody RefCommand c){return service.assign(id,c.ref(),Instant.now());}
    @PostMapping("/{id}/start") public InstitutionalWork start(@PathVariable String id){return service.start(id,Instant.now());}
    @PostMapping("/{id}/block") public InstitutionalWork block(@PathVariable String id,@RequestBody RefCommand c){return service.block(id,c.ref(),Instant.now());}
    @PostMapping("/{id}/resume") public InstitutionalWork resume(@PathVariable String id){return service.resume(id,Instant.now());}
    @PostMapping("/{id}/complete") public InstitutionalWork complete(@PathVariable String id,@RequestBody CompleteCommand c){return service.complete(id,c.outcomeRef(),c.evidenceRefs(),Instant.now());}
    @PostMapping("/{id}/cancel") public InstitutionalWork cancel(@PathVariable String id,@RequestBody RefCommand c){return service.cancel(id,c.ref(),Instant.now());}
    @GetMapping("/{id}") public InstitutionalWork get(@PathVariable String id){return service.get(id);}

    public record OriginateCommand(String workId,String objectiveRef,String organizationContextId,String originatedByWorkerId,String description){}
    public record RefCommand(String ref){}
    public record CompleteCommand(String outcomeRef,List<String> evidenceRefs){}
}
