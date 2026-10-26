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
	 * 모든 테스트가 같이 쓰는 MySQL 컨테이너 하나.
	 * 빈 메서드 안에서 만들면 Spring 컨텍스트마다 하나씩 떠서(지금 컨텍스트 2개) PC Docker 메모리(3GB)가 모자라
	 * 연결 시간 초과가 난다. 테스트끼리는 점포 ID·상품 코드·주문 ID를 따로 써서 같은 DB를 써도 섞이지 않는다.
	 * 이미 떠 있으면 다음 컨텍스트의 start()는 아무것도 하지 않고, 테스트 JVM이 끝나면 Testcontainers가 지운다.
	 */
	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
			// 테스트 데이터가 작아 성능 통계와 큰 버퍼가 필요 없다. 컨테이너 메모리를 줄인다
			.withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=32M");

	/**
	 * 테스트의 데이터베이스 연결에 사용할 MySQL 컨테이너를 돌려준다.
	 *
	 * @return 모든 컨텍스트가 같이 쓰는 MySQL 8.4 테스트 컨테이너
	 */
	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return MYSQL;
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
