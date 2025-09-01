package mdt.instance;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import mdt.instance.docker.DockerConfiguration;
import mdt.instance.docker.HarborConfiguration;
import mdt.instance.external.ExternalConfiguration;
import mdt.instance.jar.JarExecutorConfiguration;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Configuration
@EnableConfigurationProperties({
	MDTInstanceManagerConfiguration.class,
	JarExecutorConfiguration.class,
	DockerConfiguration.class,
	HarborConfiguration.class,
	ExternalConfiguration.class,
	MqttConfiguration.class,
})
public class MDTConfigurations {
}
