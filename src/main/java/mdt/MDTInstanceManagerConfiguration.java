package mdt;

import java.io.File;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@NoArgsConstructor
@Accessors(prefix = "m_")
@JsonInclude(Include.NON_NULL)
public class MDTInstanceManagerConfiguration {
	private String m_type;						// "jar", "docker", "kubernetes", "external"
	private String m_endpoint;                	// MDTInstanceManager 접속을 위한 URL
	private File m_homeDir;						// MDTInstanceManager home 디렉토리.
	private File m_bundlesDir;   				// MDTInstanceManager bundle 디렉토리.
	private File m_instancesDir;				// MDTInstanceManager instance 디렉토리.
	private File m_shareDir;					// MDT Instance들의 기본 설정 정보 디렉토리.
	private File m_defaultMDTInstanceJarFile;	// MDTInstance 수행을 위한 기본 jar 파일
	private String m_instanceEndpointFormat;	// MDTInstance 접속을 위한 URL 포맷
//	private String m_globalConfigFile;			// MDTInstance들이 공유하는 전역 설정 파일 경로
//	private File m_keyStoreFile;
}
