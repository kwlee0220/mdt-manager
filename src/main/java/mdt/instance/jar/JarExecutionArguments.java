package mdt.instance.jar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JarExecutionArguments {
	private String jarFile;
//	private String modelFile;
//	private String configFile;
	private int port = -1;
}
