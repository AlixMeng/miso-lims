package uk.ac.bbsrc.tgac.miso.persistence.impl;

import static uk.ac.bbsrc.tgac.miso.core.util.LimsUtils.isStringEmptyOrNull;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.persistence.CascadeType;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.jdbc.Work;
import org.hibernate.type.LongType;
import org.hibernate.type.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.eaglegenomics.simlims.core.Note;
import com.eaglegenomics.simlims.core.SecurityProfile;
import com.eaglegenomics.simlims.core.store.SecurityStore;
import com.google.common.annotations.VisibleForTesting;

import uk.ac.bbsrc.tgac.miso.core.data.Boxable;
import uk.ac.bbsrc.tgac.miso.core.data.Project;
import uk.ac.bbsrc.tgac.miso.core.data.Sample;
import uk.ac.bbsrc.tgac.miso.core.data.impl.SampleImpl;
import uk.ac.bbsrc.tgac.miso.core.exception.MisoNamingException;
import uk.ac.bbsrc.tgac.miso.core.service.naming.MisoNamingScheme;
import uk.ac.bbsrc.tgac.miso.core.store.ChangeLogStore;
import uk.ac.bbsrc.tgac.miso.core.store.LibraryStore;
import uk.ac.bbsrc.tgac.miso.core.store.NoteStore;
import uk.ac.bbsrc.tgac.miso.core.store.SampleQcStore;
import uk.ac.bbsrc.tgac.miso.core.store.SampleStore;
import uk.ac.bbsrc.tgac.miso.core.store.Store;
import uk.ac.bbsrc.tgac.miso.core.util.LimsUtils;
import uk.ac.bbsrc.tgac.miso.persistence.SampleDao;
import uk.ac.bbsrc.tgac.miso.sqlstore.util.DbUtils;

/**
 * This is the Hibernate DAO for Samples and serves as the bridge between Hibernate and the existing SqlStore persistence layers.
 * 
 * The data from the Sample table is loaded via Hibernate, but Hibernate cannot follow the references to Libraries and such from a Sample.
 * Therefore, this implementation loads a Sample via Hibernate, then calls into the SqlStore persistence layer to gather the remaining data
 * that Hibernate cannot access. Similarly, it then follows any necessary links on save. All the SqlStore-populated fields are marked
 * “transient” in the Sample class.
 */
@Transactional
public class HibernateSampleDao implements SampleDao, SampleStore {

  protected static final Logger log = LoggerFactory.getLogger(HibernateSampleDao.class);

  private boolean autoGenerateIdentificationBarcodes;

  private ChangeLogStore changeLogDao;

  private LibraryStore libraryDao;

  private NoteStore noteDao;

  private SampleQcStore sampleQcDao;

  private SecurityStore securityDao;

  private Store<SecurityProfile> securityProfileDao;

  @Autowired
  private SessionFactory sessionFactory;

  @Autowired
  private JdbcTemplate template;

  @Autowired
  private CacheManager cacheManager;
  
  @Autowired
  private MisoNamingScheme<Sample> sampleNamingScheme;

  public MisoNamingScheme<Sample> getSampleNamingScheme() {
    return sampleNamingScheme;
  }

  public void setSampleNamingScheme(MisoNamingScheme<Sample> sampleNamingScheme) {
    this.sampleNamingScheme = sampleNamingScheme;
  }

  @Autowired
  private MisoNamingScheme<Sample> namingScheme;

  @Override
  public MisoNamingScheme<Sample> getNamingScheme() {
    return namingScheme;
  }

  @Override
  public void setNamingScheme(MisoNamingScheme<Sample> namingScheme) {
    this.namingScheme = namingScheme;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public Long addSample(final Sample sample) throws IOException, MisoNamingException {
    // We can't generate the name until we have the ID and we don't have an ID until we start the persistence. So, we assign a temporary
    // name.
    sample.setName(generateTemporaryName());
    if (isStringEmptyOrNull(sample.getAlias()) && sampleNamingScheme.hasGeneratorFor("alias")) {
      sample.setAlias(generateTemporaryName());
    }
    generateSiblingNumberIfRequired(sample);
    long id = (Long) currentSession().save(sample);

    if (sample.getSecurityProfile() != null) {
      sessionFactory.getCurrentSession().doWork(new Work() {

        @Override
        public void execute(Connection connection) throws SQLException {
          try {
            sample.setSecurityProfileId(getSecurityProfileDao().save(sample.getSecurityProfile()));
          } catch (IOException e) {
            e.printStackTrace();
          }
        }

      });
    }

    sample.setId(id);
    
    updateParentSampleNameIfRequired(sample);
    updateSampleAliasIfRequired(sample);
    String name = namingScheme.generateNameFor("name", sample);
    sample.setName(name);

    if (sampleNamingScheme.validateField("name", sample.getName()) && sampleNamingScheme.validateField("alias", sample.getAlias())) {
      if (autoGenerateIdentificationBarcodes) {
        autoGenerateIdBarcode(sample);
      } // if !autoGenerateIdentificationBarcodes then the identificationBarcode is set by the user
    }

    currentSession().update(sample);
    
    sessionFactory.getCurrentSession().doWork(new Work() {

      @Override
      public void execute(Connection connection) throws SQLException {
        try {
          if (!sampleNamingScheme.allowDuplicateEntityNameFor("alias") && aliasExists(sample.getAlias())) {
            // Size is greater than 1, since we've just added this to the db under a temporary name.
            throw new IOException(String.format("NEW: A sample with this alias '%s' already exists in the database", sample.getAlias()));
          }
        } catch (IOException e) {
          throw new SQLException(e);
        }

      }
    });
    persistSqlStore(sample);
    return id;
  }
  
  private void generateSiblingNumberIfRequired(Sample sample) {
    if (sample.getSampleAdditionalInfo() != null && sample.getParent() != null) {
      Query query = currentSession().createQuery("select max(siblingNumber) "
          + "from SampleAdditionalInfoImpl "
          + "where parentId = :parentId "
          + "and sampleClassId = :sampleClassId");
      query.setLong("parentId", sample.getParent().getId());
      query.setLong("sampleClassId", sample.getSampleAdditionalInfo().getSampleClass().getSampleClassId());
      Number result = ((Number) query.uniqueResult());
      int siblingNumber = result == null ? 1 : result.intValue() + 1;
      sample.getSampleAdditionalInfo().setSiblingNumber(siblingNumber);
    }
  }

  @VisibleForTesting
  void updateParentSampleNameIfRequired(Sample child) throws MisoNamingException, IOException {
    if (child.getSampleAdditionalInfo() != null
        && hasTemporaryName(child.getParent())
        && child.getParent().getId() > Sample.UNSAVED_ID) {
      String name = namingScheme.generateNameFor("name", child.getSampleAdditionalInfo().getParent());
      child.getSampleAdditionalInfo().getParent().setName(name);
    }
  }
  
  private void updateSampleAliasIfRequired(Sample sample) throws MisoNamingException {
    if (hasTemporaryAlias(sample) && sample.getId() > Sample.UNSAVED_ID 
        && sampleNamingScheme.hasGeneratorFor("alias") ) {
      String alias = sampleNamingScheme.generateNameFor("alias", sample);
      sample.setAlias(alias);
    }
  }

  /**
   * Generates a unique barcode. Note that the barcode will change when the alias is changed.
   * 
   * @param sample
   */
  public void autoGenerateIdBarcode(Sample sample) {
    String barcode = sample.getName() + "::" + sample.getAlias();
    sample.setIdentificationBarcode(barcode);
  }

  @Override
  public int count() throws IOException {
    System.out.println(template.toString());
    return getSample().size();
  }

  private Session currentSession() {
    return getSessionFactory().getCurrentSession();
  }

  @Override
  public void deleteSample(Sample sample) {
    currentSession().delete(sample);

  }

  /**
   * Fix up a Sample loaded by Hibernate by gathering the SqlStore-persisted information and mutating the object.
   * 
   * @returns the original object after mutation.
   */
  private Sample fetchSqlStore(Sample sample) throws IOException {
    if (sample == null) return null;
    // Now we have to reconstitute all the things that aren't covered by Hibernate.
    sample.setSecurityProfile(securityDao.getSecurityProfileById(sample.getSecurityProfileId()));

    sample.getLibraries().clear();
    sample.getLibraries().addAll(libraryDao.listBySampleId(sample.getId()));

    sample.getSampleQCs().clear();
    sample.getSampleQCs().addAll(sampleQcDao.listBySampleId(sample.getId()));

    sample.getNotes().clear();
    sample.getNotes().addAll(noteDao.listBySample(sample.getId()));

    sample.getChangeLog().clear();
    sample.getChangeLog().addAll(changeLogDao.listAllById("Sample", sample.getId()));
    
    if (sample.getSampleAdditionalInfo() != null) {
      sample.getSampleAdditionalInfo().setChildren(listByParentId(sample.getId()));
    }

    return sample;
  }

  /**
   * Fixup a collection of Samples loaded by Hibernate. This mutates the collection's contents.
   * 
   * @return the original collection, having had it's contents mutated
   */
  private <T extends Iterable<Sample>> T fetchSqlStore(T iterable) throws IOException {
    for (Sample s : iterable) {
      fetchSqlStore(s);
    }
    return iterable;
  }

  @Override
  public Sample get(long id) throws IOException {
    return getSample(id);
  }

  public boolean getAutoGenerateIdentificationBarcodes() {
    return autoGenerateIdentificationBarcodes;
  }

  @Override
  public Sample getByBarcode(String barcode) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl where identificationBarcode = :barcode");
    query.setString("barcode", barcode);
    return fetchSqlStore((Sample) query.uniqueResult());
  }

  @Override
  public Collection<Sample> getByBarcodeList(List<String> barcodeList) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl where identificationBarcode in (:barcodes)");
    query.setParameterList("barcodes", barcodeList, StringType.INSTANCE);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }
  
  @Override
  public Collection<Sample> getByIdList(List<Long> idList) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl where sampleId in (:ids)");
    query.setParameterList("ids", idList, LongType.INSTANCE);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }

  @Override
  public Boxable getByPositionId(long positionId) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl where boxPositionId = :posn");
    query.setLong("posn", positionId);
    return fetchSqlStore((Sample) query.uniqueResult());
  }

  public ChangeLogStore getChangeLogDao() {
    return changeLogDao;
  }

  public JdbcTemplate getJdbcTemplate() {
    return template;
  }

  public LibraryStore getLibraryDao() {
    return libraryDao;
  }

  public NoteStore getNoteDao() {
    return noteDao;
  }

  @Override
  public List<Sample> getSample() throws IOException {
    Query query = currentSession().createQuery("from SampleImpl");
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }

  @Override
  public Sample getSample(Long id) throws IOException {
    return fetchSqlStore((Sample) currentSession().get(SampleImpl.class, id));
  }

  public SampleQcStore getSampleQcDao() {
    return sampleQcDao;
  }

  public SecurityStore getSecurityDao() {
    return securityDao;
  }

  public Store<SecurityProfile> getSecurityProfileDao() {
    return securityProfileDao;
  }

  /**
   * Pull a Sample without following all of the links. At the present time, this means just loading the object from Hibernate.
   */
  @Override
  public Sample lazyGet(long id) throws IOException {
    return (Sample) currentSession().get(SampleImpl.class, id);
  }

  @Override
  public Collection<Sample> listAll() throws IOException {
    return getSample();
  }

  @Override
  public Collection<Sample> listAllByReceivedDate(long limit) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl order by receivedDate desc");
    query.setMaxResults((int) limit);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }

  @Override
  public Collection<String> listAllSampleTypes() throws IOException {
    return getJdbcTemplate().queryForList("SELECT name FROM SampleType", String.class);
  }

  @Override
  public Collection<Sample> listAllWithLimit(long limit) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl");
    query.setMaxResults((int) limit);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }

  /**
   * Determines if an alias exists already. This method is specifically used during the creation of a sample and does not call fetchSqlStore
   * since the partial state of the new sample will cause it to fail.
   * 
   * @param alias
   *          See if this alias already exists.
   * @return True if the alias already exists.
   * @throws IOException
   *           If there are difficulties reading from the database.
   */
  private boolean aliasExists(String alias) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl where alias = :alias");
    query.setString("alias", alias);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    // Need to check greater than one since the alias was just added as part of sample creation. If any more of the sample creation
    // fails the transaction will be rolled back and the sample entry removed.
    return records.size() > 1;
  }

  @Override
  public Collection<Sample> listByAlias(String alias) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl where alias = :alias");
    query.setString("alias", alias);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }

  @Override
  public Collection<Sample> listByExperimentId(long experimentId) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl where experiment.id like :id");
    query.setLong("id", experimentId);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }

  @Override
  public Collection<Sample> listByProjectId(long projectId) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl where project.id like :id");
    query.setLong("id", projectId);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }

  @Override
  public Collection<Sample> listBySearch(String querystr) throws IOException {
    Query query = currentSession().createQuery(
        "from SampleImpl where identificationBarcode like :query or name LIKE :query or alias like :query or description like :query or scientificName like :query");
    query.setString("query", querystr);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }

  @Override
  public Collection<Sample> listBySubmissionId(long submissionId) throws IOException {
    Query query = currentSession().createQuery("from SampleImpl where submissionId like :id");
    query.setLong("id", submissionId);
    @SuppressWarnings("unchecked")
    List<Sample> records = query.list();
    return fetchSqlStore(records);
  }
  
  private Set<Sample> listByParentId(long parentId) {
    Query query = currentSession().createQuery(
        "select s from SampleImpl s "
        + "join s.sampleAdditionalInfo sai "
        + "join sai.parent p "
        + "where p.sampleId = :id");
    query.setLong("id", parentId);
    @SuppressWarnings("unchecked")
    List<Sample> samples = query.list();
    return new HashSet<Sample>(samples);
  }

  /**
   * Write all the non-Hibernate data from a Sample that aren't persisted manually in the controllers.
   */
  private void persistSqlStore(Sample sample) throws IOException {
    Cache cache = cacheManager == null ? null : cacheManager.getCache(LimsUtils.noddyCamelCaseify(Project.class.getSimpleName()) + "Cache");
    if (cache != null) cache.remove(DbUtils.hashCodeCacheKeyFor(sample.getProject().getId()));

    // Now we have to persist all the things that aren't covered by Hibernate. Turns out, just notes.

    for (Note n : sample.getNotes()) {
      noteDao.saveSampleNote(sample, n);
    }
  }

  @Override
  public boolean remove(Sample t) throws IOException {
    deleteSample(t);
    return true;
  }

  @Override
  public long save(Sample t) throws IOException {
    if (t.getId() == SampleImpl.UNSAVED_ID) {
      try {
        return addSample(t);
      } catch (MisoNamingException e) {
        throw new IOException("Bad name", e);
      }
    } else {
      update(t);
      return t.getId();
    }
  }

  public void setAutoGenerateIdentificationBarcodes(boolean autoGenerateIdentificationBarcodes) {
    this.autoGenerateIdentificationBarcodes = autoGenerateIdentificationBarcodes;
  }

  @Override
  public void setCascadeType(CascadeType cascadeType) {
  }

  public void setChangeLogDao(ChangeLogStore changeLogDao) {
    this.changeLogDao = changeLogDao;
  }

  public void setJdbcTemplate(JdbcTemplate template) {
    this.template = template;
  }

  public void setLibraryDao(LibraryStore libraryDao) {
    this.libraryDao = libraryDao;
  }

  public void setNoteDao(NoteStore noteDao) {
    this.noteDao = noteDao;
  }

  public void setSampleQcDao(SampleQcStore sampleQcDao) {
    this.sampleQcDao = sampleQcDao;
  }

  public void setSecurityDao(SecurityStore securityDao) {
    this.securityDao = securityDao;
  }

  public void setSecurityProfileDao(Store<SecurityProfile> securityProfileDao) {
    this.securityProfileDao = securityProfileDao;
  }

  @Override
  public void update(Sample sample) throws IOException {
    if (sample.getSecurityProfile() != null) {
      sample.setSecurityProfileId(sample.getSecurityProfile().getProfileId());
    }
    currentSession().update(sample);
    persistSqlStore(sample);
  }

  public SessionFactory getSessionFactory() {
    return sessionFactory;
  }

  public void setSessionFactory(SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  @Override
  public Map<String, Integer> getSampleColumnSizes() throws IOException {
    return DbUtils.getColumnSizes(template, "Sample");
  }

  static final private String TEMPORARY_NAME_PREFIX = "TEMPORARY_S";

  /**
   * Generate a temporary name using a UUID.
   * 
   * @return Temporary name
   */
  static public String generateTemporaryName() {
    return TEMPORARY_NAME_PREFIX + UUID.randomUUID();
  }

  static public boolean hasTemporaryName(Sample sample) {
    boolean result = false;
    if (sample != null && sample.getName() != null) {
      result = sample.getName().startsWith(TEMPORARY_NAME_PREFIX);
    }
    return result;
  }
  
  static public boolean hasTemporaryAlias(Sample sample) {
    return sample.getAlias().startsWith(TEMPORARY_NAME_PREFIX);
  }
}
