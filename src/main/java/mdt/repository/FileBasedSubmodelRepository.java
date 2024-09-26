package mdt.repository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonSerializer;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;

import utils.InternalException;
import utils.Throwables;
import utils.fostore.DefaultFileObjectStore;
import utils.fostore.FileObjectHandler;
import utils.stream.FStream;

import mdt.model.AASUtils;
import mdt.model.ResourceNotFoundException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class FileBasedSubmodelRepository implements SubmodelRepositoryProvider {
	private final DefaultFileObjectStore<String, Submodel> m_store;
	
	public FileBasedSubmodelRepository(File topDir) throws IOException {
		m_store = new DefaultFileObjectStore<>(topDir, new SubmodelHandler(topDir));
	}

	@Override
	public void close() throws Exception {
	}

	@Override
	public List<ServiceIdentifier> getAllSubmodels() {
		try {
			return FStream.from(m_store.getFileObjectAll())
							.map(this::toIdentifier)
							.toList();
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public ServiceIdentifier getSubmodelById(String id) {
		try {
			Submodel submodel = m_store.get(id)
										.orElseThrow(() -> new ResourceNotFoundException("SubmodelService", id));
			return toIdentifier(submodel);
		}
		catch ( ResourceNotFoundException e ) {
			throw e;
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public List<ServiceIdentifier> getAllSubmodelBySemanticId(String semanticId) {
		try {
			return FStream.from(m_store.getFileObjectAll())
							.filter(sm -> semanticId.equals(sm.getSemanticId()))
							.map(this::toIdentifier)
							.toList();
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public List<ServiceIdentifier> getAllSubmodelsByIdShort(String idShort) {
		try {
			return FStream.from(m_store.getFileObjectAll())
							.filter(sm -> idShort.equals(sm.getIdShort()))
							.map(this::toIdentifier)
							.toList();
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public ServiceIdentifier addSubmodel(Submodel submodel) {
		try {
			m_store.insert(submodel.getId(), submodel);
			return toIdentifier(submodel);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public ServiceIdentifier updateSubmodelById(Submodel submodel) {
		try {
			m_store.remove(submodel.getId());
			m_store.insert(submodel.getId(), submodel);
			return toIdentifier(submodel);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public void removeSubmodelById(String id) {
		try {
			m_store.remove(id);
		}
		catch ( IOException e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}
	
	private ServiceIdentifier toIdentifier(Submodel sm) {
		return new ServiceIdentifier(sm.getId());
	}

	private static final class SubmodelHandler implements FileObjectHandler<String, Submodel> {
		private final File m_rootDir;
		private final JsonDeserializer m_deser = new JsonDeserializer();
		private final JsonSerializer m_ser = new JsonSerializer();
		
		SubmodelHandler(File rootDir) {
			m_rootDir = rootDir;
		}
		
		@Override
		public Submodel readFileObject(File file) throws IOException, ExecutionException {
			try ( FileInputStream fis = new FileInputStream(file) ) {
				return m_deser.read(fis, Submodel.class);
			}
			catch ( IOException e ) {
				throw e;
			}
			catch ( Exception e ) {
				throw new ExecutionException(e);
			}
		}

		@Override
		public void writeFileObject(Submodel aas, File file) throws IOException, ExecutionException {
			try ( FileOutputStream fos = new FileOutputStream(file) ) {
				m_ser.write(fos, aas);
			}
			catch ( IOException e ) {
				throw e;
			}
			catch ( Exception e ) {
				throw new ExecutionException(e);
			}
		}

		@Override
		public File toFile(String key) {
			String encodedName = AASUtils.encodeBase64UrlSafe(key);
			return new File(m_rootDir, encodedName);
		}

		@Override
		public String toFileObjectKey(File file) {
			return AASUtils.encodeBase64UrlSafe(file.getName());
		}

		@Override
		public boolean isVallidFile(File file) {
			return file.getParentFile().equals(m_rootDir);
		}
	}
}
