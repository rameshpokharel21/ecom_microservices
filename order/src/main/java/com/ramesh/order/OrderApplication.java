package com.ramesh.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

//@EnableAsync is here for exactly one method: OrderEventPublisher.publishOrderCreated.
//Boot does not enable @Async by default, so without this the annotation there is
//silently ignored - the method still runs, just on the caller's thread, which is the
//behaviour it exists to avoid. It uses Boot's auto-configured executor
//(spring.task.execution.*); no custom pool is defined.
@EnableAsync
@SpringBootApplication
public class OrderApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderApplication.class, args);
	}

}
