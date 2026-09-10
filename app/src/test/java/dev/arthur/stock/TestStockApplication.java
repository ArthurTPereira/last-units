package dev.arthur.stock;

import org.springframework.boot.SpringApplication;

public class TestStockApplication {

	public static void main(String[] args) {
		SpringApplication.from(StockApplication::main)
			.with(TestcontainersConfiguration.class)
			.run(args);
	}

}
