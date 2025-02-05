package mdt.instance.docker;

import java.io.Closeable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Preconditions;

import lombok.Data;

import utils.InternalException;
import utils.Utilities;
import utils.func.Funcs;
import utils.http.OkHttpClientUtils;
import utils.stream.FStream;

import mdt.client.HttpRESTfulClientOld;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTHarborClient implements Closeable {
	public static final JsonMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule()).build();
	
	private final HarborConfiguration m_conf;
	private HttpRESTfulClientOld m_client;
	private String m_credential;
	
	public MDTHarborClient(HarborConfiguration conf) {
		m_conf = conf;
		
		try {
			OkHttpClient httpClient = OkHttpClientUtils.newTrustAllOkHttpClientBuilder().build();
			m_client = new HttpRESTfulClientOld(httpClient, conf.getEndpoint(), MAPPER);
			if ( conf.getUser() != null && conf.getPassword() != null ) {
				m_credential = Credentials.basic(conf.getUser(), conf.getPassword());
			}
		}
		catch ( Exception e ) {
			throw new InternalException("Failed to open HttpClient, cause=" + e);
		}
	}

	@Override
	public void close() {
	}
	
	public List<String> getInstanceImageAll() {
		return FStream.from(getRepositoryAll(m_conf.getProject()))
						.filter(repo -> existsLatestTag(getArtifactAll(m_conf.getProject(), repo.getName())))
						.map(repo -> repo.getName())
						.toList();
	}
	
	public void removeInstanceImage(String repoName) {
		Preconditions.checkState(m_credential != null, "No credential");
		
		String url = String.format("%s/projects/%s/repositories/%s",
									m_client.getEndpoint(), m_conf.getProject(), repoName);

		Request req = new Request.Builder()
								.header("Authorization", m_credential)
								.url(url).delete().build();
		m_client.call(req, void.class);
	}
	
	private static boolean existsLatestTag(List<Artifact> artifacts) {
		return FStream.from(artifacts)
						.exists(art -> Funcs.exists(art.tags, tg -> tg.getName().equals("latest")));
	}
	
	private List<Repository> getRepositoryAll(String projectName) {
		String url = String.format("%s/projects/%s/repositories", m_client.getEndpoint(), projectName);

		Request req = new Request.Builder().url(url).get().build();
															
		return m_client.call(req, REPOSITORY_LIST_TYPE);
	}
	
	private List<Artifact> getArtifactAll(String projectName, String repoName) {
		String url = String.format("%s/projects/%s/repositories/%s/artifacts",
									m_client.getEndpoint(), projectName, repoName);

		Request req = new Request.Builder().url(url).get().build();
		return m_client.call(req, ARTIFACT_LIST_TYPE);
	}
	
	
	
	
	
	
	
	public static final void main(String... args) throws Exception {
		HarborConfiguration conf = new HarborConfiguration();
		conf.setEndpoint("https://docker.zento.co.kr/api/v2.0");
		conf.setProject("mdt-twins");
		conf.setUser("etri");
		conf.setPassword("zento");
		
		MDTHarborClient harbor = new MDTHarborClient(conf);
		List<String> instList = harbor.getInstanceImageAll();
		for ( String art: instList ) {
			System.out.println(art);
		}
		
		harbor.removeInstanceImage("mdt-twin-test");
		
//		List<Repository> repoList = harbor.getRepositoryAll("mdt-twins");
//		List<Artifact> artList = harbor.getArtifactAll("mdt-twins", "mdt-twin-surface");
//		for ( Artifact art: artList ) {
//			System.out.println(art);
//		}
//		
//		harbor.removeRepository("mdt-twins", "mdt-twin-surface");
	}

	private static final TypeReference<List<Repository>> REPOSITORY_LIST_TYPE
														= new TypeReference<List<Repository>>(){};
	private static final TypeReference<List<Artifact>> ARTIFACT_LIST_TYPE
														= new TypeReference<List<Artifact>>(){};
	@Data
	@JsonIgnoreProperties(ignoreUnknown=true)
	@JsonInclude(Include.NON_NULL)
	public static class Repository {
		private int id;
		@JsonProperty("project_id") private int projectId;
		private String name;
		private String description;
		@JsonProperty("artifact_count") private int artifactCount;
		
		public String getName() {
			return Utilities.split(this.name, '/')._2;
		}
	}
	
	@Data
	@JsonIgnoreProperties(ignoreUnknown=true)
	@JsonInclude(Include.NON_NULL)
	public static class Tag {
		private int id;
		@JsonProperty("repository_id") private int repositoryId;
		@JsonProperty("artifact_id") private int artifactId;
		private String name;
	}
	
	@Data
	@JsonInclude(Include.NON_NULL)
	public static class Label {
		private int id;
		private String name;
		private String description;
		@JsonProperty("project_id") private int projectId;
	}
	
	@Data
	@JsonIgnoreProperties(ignoreUnknown=true)
	@JsonInclude(Include.NON_NULL)
	public static class Artifact {
		private int id;
		private String type;
		@JsonProperty("media_type") private String mediaType;
		@JsonProperty("project_id") private int projectId;
		private long size;
		private List<Tag> tags;
		private List<Label> labels;
	}
}
