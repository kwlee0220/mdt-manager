package mdt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SpringBootApplication
@ConfigurationPropertiesScan("mdt")
public class MDTInstanceApplication {
	public static void main(String[] args) throws Exception {
        SpringApplication.run(MDTInstanceApplication.class, args);
	}
}
