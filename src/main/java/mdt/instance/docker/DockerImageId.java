package mdt.instance.docker;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class DockerImageId {
	private final String m_repository;
	private final String m_tag;
	
	public DockerImageId(String repository, String tag) {
		m_repository = repository;
		m_tag = tag;
	}
	
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
	
	public String getRepository() {
		return m_repository;
	}

	public String getTag() {
		return m_tag;
	}
	
	public String getFullName() {
		return this.m_repository + "/" + this.m_tag;
	}
	
	@Override
	public String toString() {
		return getFullName();
	}
	
	@Override
	public boolean equals(Object obj) {
		if ( obj == this ) {
			return true;
		}
		else if ( obj == null || obj.getClass() != DockerImageId.class ) {
            return false;
		}
		
		DockerImageId other = (DockerImageId) obj;
		return m_repository.equals(other.m_repository) && m_tag.equals(other.m_tag);
	}
}