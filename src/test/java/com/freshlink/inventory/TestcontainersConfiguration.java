package com.freshlink.inventory;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	/**
	 * 모든 테스트가 같이 쓰는 MySQL 컨테이너 하나. 테스트 JVM이 뜰 때 시작하고 JVM이 끝나면 Testcontainers(Ryuk)가 지운다.
	 *
	 * <p>컨테이너를 {@code @Bean}으로 등록하면 Spring Boot가 컨텍스트마다 하나씩 띄우고(지금 컨텍스트 2개), 컨텍스트가 닫힐 때
	 * 컨테이너도 멈춘다. 컨테이너 2개는 PC Docker 메모리(3GB)가 모자라 연결 시간 초과가 나고, static 하나를 빈으로 내놓으면
	 * 한 컨텍스트가 닫힐 때 다른 컨텍스트가 쓰는 컨테이너가 멈춘다. 그래서 컨테이너는 Spring 밖에서 관리하고
	 * Spring에는 연결 정보({@link JdbcConnectionDetails})만 준다.
	 *
	 * <p>테스트끼리는 점포 ID·상품 코드·주문 ID를 따로 써서 같은 DB를 써도 섞이지 않는다.
	 */
	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
			// 테스트 데이터가 작아 성능 통계와 큰 버퍼가 필요 없다. 컨테이너 메모리를 줄인다
			.withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=32M");

	static {
		MYSQL.start();
	}

	/**
	 * 공유 컨테이너의 연결 정보. DataSource 자동 설정이 application.yml의 DB_URL 대신 이것을 쓴다.
	 *
	 * @return 컨테이너의 JDBC 주소·계정
	 */
	@Bean
	JdbcConnectionDetails mysqlConnectionDetails() {
		return new JdbcConnectionDetails() {
			@Override
			public String getJdbcUrl() {
				return MYSQL.getJdbcUrl();
			}

			@Override
			public String getUsername() {
				return MYSQL.getUsername();
			}

			@Override
			public String getPassword() {
				return MYSQL.getPassword();
			}
		};
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
