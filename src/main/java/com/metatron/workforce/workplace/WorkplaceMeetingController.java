package com.metatron.workforce.workplace;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only Workplace observation surface. Meeting mutation happens only through institutional interaction routing. */
@RestController
@RequestMapping("/workforce/workplace/meetings")
public final class WorkplaceMeetingController {
    private final WorkplaceMeetingService meetings;

    public WorkplaceMeetingController(WorkplaceMeetingService meetings) {
        this.meetings = meetings;
    }

    @GetMapping
    public List<MeetingRecord> list() { return meetings.list(); }

    @GetMapping("/{meetingId}")
    public ResponseEntity<MeetingRecord> get(@PathVariable String meetingId) {
        try {
            return ResponseEntity.ok(meetings.require(meetingId));
        } catch (IllegalArgumentException missing) {
            return ResponseEntity.notFound().build();
        }
    }
}
