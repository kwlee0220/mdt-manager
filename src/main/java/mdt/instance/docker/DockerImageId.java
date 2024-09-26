package mdt.instance.docker;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Data
@Builder
@EqualsAndHashCode
public class DockerImageId {
	private String repository;
	private String tag;
	
	public static DockerImageId parse(String imageId) {
		String[] parts = imageId.split(":");
		if ( parts.length == 2 ) {
			return new DockerImageId(parts[0], parts[1]);
		}
		else if ( parts.length == 1 ) {
			return new DockerImageId(parts[0], "latest");
		}
		else {
			throw new RuntimeException("invalid docker image id: " + imageId);
		}
	}
	
	public String getFullName() {
		return this.repository + "/" + this.tag;
	}
	
	@Override
	public String toString() {
		return getFullName();
	}
}