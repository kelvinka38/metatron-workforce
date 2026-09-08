package com.metatron.workforce.work;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class WorkConfiguration {
    @Bean WorkStateStore workStateStore(
            @Value("${METATRON_WORKFORCE_WORK_STATE_PATH:/var/lib/metatron-workforce/institutional-work.json}") String configured){
        return new FileWorkStateStore(Path.of(configured));
    }
    @Bean WorkService workService(WorkStateStore store){return new WorkService(store);}
}
