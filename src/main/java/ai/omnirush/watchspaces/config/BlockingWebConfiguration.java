package ai.omnirush.watchspaces.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.config.BlockingExecutionConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/** Keeps synchronous JDBC and password hashing off reactive request threads. */
@Configuration
public class BlockingWebConfiguration implements WebFluxConfigurer {
    @Bean
    public ThreadPoolTaskExecutor backendBlockingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("backend-blocking-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(128);
        return executor;
    }

    @Override
    public void configureBlockingExecution(BlockingExecutionConfigurer configurer) {
        // WebFlux routes non-reactive controller methods to this bounded pool.
        configurer.setExecutor(backendBlockingExecutor());
    }
}
