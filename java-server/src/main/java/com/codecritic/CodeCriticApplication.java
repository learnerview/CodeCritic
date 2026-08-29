package com.codecritic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneRedisAutoConfiguration;

@SpringBootApplication(exclude = {
        // We provide the single RedisConnectionFactory ourselves (from REDIS_URL / spring.redis.url),
        // so SimplyDone4J must not spin up its own standalone factory that ignores that config.
        // Boot's RedisAutoConfiguration is intentionally NOT excluded: it still contributes the
        // `redisTemplate` bean (used by Spring Data Redis infrastructure) without creating a second
        // connection factory, because ours already satisfies @ConditionalOnMissingBean.
        SimplyDoneRedisAutoConfiguration.class
})
public class CodeCriticApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeCriticApplication.class, args);
    }
}
