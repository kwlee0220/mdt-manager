package mdt.instance.docker;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Data
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class HarborConfiguration {
	private String host;
	private String endpoint;
	private String project;
	private String user;
	private String password;
}
