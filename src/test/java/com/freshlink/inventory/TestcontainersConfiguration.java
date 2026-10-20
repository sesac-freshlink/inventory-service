package com.freshlink.inventory;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	/**
	 * 테스트의 데이터베이스 연결에 사용할 MySQL 컨테이너를 생성한다.
	 *
	 * @return MySQL 8.4 테스트 컨테이너
	 */
	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
	}

	/**
	 * 테스트에서 실제 AWS SQS에 접근하지 않도록 모의 템플릿을 생성한다.
	 *
	 * @return SQS 작업에 사용할 모의 템플릿
	 */
	@Bean
	SqsTemplate sqsTemplate() {
		return mock(SqsTemplate.class);
	}
}
