package com.metatron.workforce.work;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkService {
    private final Map<String,InstitutionalWork> work=new ConcurrentHashMap<>();
    private final WorkStateStore store;

    public WorkService(){this(new WorkStateStore(){
        private List<InstitutionalWork> state=List.of();
        public List<InstitutionalWork> load(){return state;}
        public void save(List<InstitutionalWork> values){state=List.copyOf(values);}
    });}

    public WorkService(WorkStateStore store){
        this.store=Objects.requireNonNull(store);
        store.load().forEach(w->work.put(w.workId(),w));
    }

    public synchronized InstitutionalWork originate(String id,String objectiveRef,String organizationContextId,
            String workerId,String description,Instant at){
        InstitutionalWork w=new InstitutionalWork(id,objectiveRef,organizationContextId,workerId,description,
                InstitutionalWork.Status.ORIGINATED,null,null,null,List.of(),at,at);
        if(work.putIfAbsent(id,w)!=null) throw new IllegalStateException("work already exists");
        persist(); return w;
    }

    public synchronized InstitutionalWork linkProposal(String id,String proposalRef,Instant at){
        require(proposalRef,"proposalRef"); return update(id,InstitutionalWork.Status.PROPOSED,proposalRef,null,null,null,at);
    }
    public synchronized InstitutionalWork assign(String id,String assignmentRef,Instant at){
        require(assignmentRef,"assignmentRef"); return update(id,InstitutionalWork.Status.ASSIGNED,null,assignmentRef,null,null,at);
    }
    public synchronized InstitutionalWork start(String id,Instant at){ return update(id,InstitutionalWork.Status.IN_PROGRESS,null,null,null,null,at); }
    public synchronized InstitutionalWork block(String id,String evidenceRef,Instant at){
        require(evidenceRef,"evidenceRef"); return update(id,InstitutionalWork.Status.BLOCKED,null,null,null,List.of(evidenceRef),at);
    }
    public synchronized InstitutionalWork resume(String id,Instant at){
        InstitutionalWork old=get(id); if(old.status()!=InstitutionalWork.Status.BLOCKED) throw new IllegalStateException("work is not blocked");
        return update(id,InstitutionalWork.Status.IN_PROGRESS,null,null,null,null,at);
    }
    public synchronized InstitutionalWork complete(String id,String outcomeRef,List<String> evidenceRefs,Instant at){
        require(outcomeRef,"outcomeRef");
        if(evidenceRefs==null||evidenceRefs.stream().filter(Objects::nonNull).map(String::trim).noneMatch(s->!s.isBlank()))
            throw new IllegalArgumentException("completed work requires evidence");
        return update(id,InstitutionalWork.Status.COMPLETED,null,null,outcomeRef,evidenceRefs,at);
    }
    public synchronized InstitutionalWork cancel(String id,String evidenceRef,Instant at){
        require(evidenceRef,"evidenceRef"); return update(id,InstitutionalWork.Status.CANCELLED,null,null,null,List.of(evidenceRef),at);
    }

    public InstitutionalWork get(String id){return Optional.ofNullable(work.get(id)).orElseThrow(()->new NoSuchElementException("work not found"));}
    public List<InstitutionalWork> forObjective(String objectiveRef){return work.values().stream().filter(w->w.objectiveRef().equals(objectiveRef)).toList();}

    private InstitutionalWork update(String id,InstitutionalWork.Status status,String proposalRef,String assignmentRef,
            String outcomeRef,List<String> evidenceRefs,Instant at){
        InstitutionalWork old=get(id); if(old.terminal()) throw new IllegalStateException("terminal work cannot transition");
        InstitutionalWork next=new InstitutionalWork(old.workId(),old.objectiveRef(),old.organizationContextId(),old.originatedByWorkerId(),
                old.description(),status,proposalRef!=null?proposalRef:old.proposalRef(),assignmentRef!=null?assignmentRef:old.assignmentRef(),
                outcomeRef!=null?outcomeRef:old.outcomeRef(),evidenceRefs!=null?merge(old.evidenceRefs(),evidenceRefs):old.evidenceRefs(),old.createdAt(),Objects.requireNonNull(at));
        work.put(id,next); persist(); return next;
    }
    private static List<String> merge(List<String>a,List<String>b){List<String>x=new ArrayList<>(a);b.stream().filter(Objects::nonNull).map(String::trim).filter(s->!s.isBlank()).forEach(x::add);return x.stream().distinct().toList();}
    private void persist(){store.save(List.copyOf(work.values()));}
    private static void require(String v,String n){if(v==null||v.isBlank()) throw new IllegalArgumentException(n+" required");}
}
