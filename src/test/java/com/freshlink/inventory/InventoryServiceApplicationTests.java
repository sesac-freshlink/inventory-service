package com.freshlink.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class InventoryServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
