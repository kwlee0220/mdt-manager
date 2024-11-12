package mdt.instance.docker;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Data
@NoArgsConstructor
public class DockerConfiguration {
	private String dockerEndpoint;
	private String imageName;
}
