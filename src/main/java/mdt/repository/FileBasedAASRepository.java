package mdt.repository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonSerializer;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;

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
public class FileBasedAASRepository implements AssetAdministrationShellRepositoryProvider {
	private final DefaultFileObjectStore<String, AssetAdministrationShell> m_store;
	
	public FileBasedAASRepository(File topDir) throws IOException {
		m_store = new DefaultFileObjectStore<>(topDir, new AASHandler(topDir));
	}

	@Override
	public List<ServiceIdentifier> getAllAssetAdministrationShells() {
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
	public ServiceIdentifier getAssetAdministrationShellById(String aasId) {
		try {
			AssetAdministrationShell aas
					= m_store.get(aasId)
							.orElseThrow(() -> new ResourceNotFoundException("AssetAdministrationShell", aasId));
			return toIdentifier(aas);
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
	public List<ServiceIdentifier> getAllAssetAdministrationShellsByAssetId(String key) {
		try {
			return FStream.from(m_store.getFileObjectAll())
							.filter(aas -> key.equals(aas.getAssetInformation().getGlobalAssetId()))
							.map(this::toIdentifier)
							.toList();
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public List<ServiceIdentifier> getAssetAdministrationShellByIdShort(String idShort) {
		try {
			return FStream.from(m_store.getFileObjectAll())
							.filter(aas -> idShort.equals(aas.getIdShort()))
							.map(this::toIdentifier)
							.toList();
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public ServiceIdentifier addAssetAdministrationShell(AssetAdministrationShell aas) {
		try {
			m_store.insert(aas.getId(), aas);
			return toIdentifier(aas);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public ServiceIdentifier updateAssetAdministrationShellById(AssetAdministrationShell aas) {
		try {
			m_store.remove(aas.getId());
			m_store.insert(aas.getId(), aas);
			return toIdentifier(aas);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	@Override
	public void removeAssetAdministrationShellById(String id) {
		try {
			m_store.remove(id);
		}
		catch ( IOException e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	public ServiceIdentifier toIdentifier(AssetAdministrationShell aas) {
		return new ServiceIdentifier(aas.getId());
	}

	private static final class AASHandler implements FileObjectHandler<String, AssetAdministrationShell> {
		private final File m_rootDir;
		private final JsonDeserializer m_deser = new JsonDeserializer();
		private final JsonSerializer m_ser = new JsonSerializer();
		
		AASHandler(File rootDir) {
			m_rootDir = rootDir;
		}
		
		@Override
		public AssetAdministrationShell readFileObject(File file) throws IOException, ExecutionException {
			try ( FileInputStream fis = new FileInputStream(file) ) {
				return m_deser.read(fis, AssetAdministrationShell.class);
			}
			catch ( IOException e ) {
				throw e;
			}
			catch ( Exception e ) {
				throw new ExecutionException(e);
			}
		}

		@Override
		public void writeFileObject(AssetAdministrationShell aas, File file) throws IOException,
																					ExecutionException {
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
			return AASUtils.decodeBase64UrlSafe(file.getName());
		}

		@Override
		public boolean isVallidFile(File file) {
			return file.getParentFile().equals(m_rootDir);
		}
	}

	@Override
	public void close() throws Exception {
	}
}
