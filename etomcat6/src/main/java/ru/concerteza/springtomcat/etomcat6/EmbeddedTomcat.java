package ru.concerteza.springtomcat.etomcat6;

import org.apache.catalina.*;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardThreadExecutor;
import org.apache.catalina.startup.Embedded;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.naming.resources.FileDirContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import ru.concerteza.springtomcat.etomcat6.config.*;
import ru.concerteza.springtomcat.etomcat6.context.EmbeddedSpringContext;
import ru.concerteza.springtomcat.etomcat6.valves.PostCharacterEncodingValve;

import java.io.File;

import static java.io.File.separator;

/**
 * User: alexey Date: 6/21/11
 */

public class EmbeddedTomcat implements ApplicationContextAware {
    private final Log logger = LogFactory.getLog(EmbeddedTomcat.class);

    private boolean started = false;
    private Embedded embedded;
    private Executor executor;

    private ApplicationContext springContext;
    private GeneralProperties generalProps = new GeneralProperties();
    private FsProperties fsProps = new FsProperties();
    private HostProperties hostProps = new HostProperties();
    private ContextProperties contextProps = new ContextProperties();
    private ConnectorProperties connectorProps = new ConnectorProperties();
    private NioProperties nioProps = new NioProperties();
    private SocketProperties socketProps = new SocketProperties();
    private SslProperties sslProps = new SslProperties();
    private ExecutorProperties executorProps = new ExecutorProperties();

    public synchronized void start(File baseDir) {
        if (started) throw new RuntimeException("ETomcat is already started");
        try {
            doStart(baseDir);
        } catch (LifecycleException e) {
            doStop();
            throw new RuntimeException("Exception occured on starting ETomcat", e);
        }
    }

    public synchronized void stop() {
        if (started) doStop();
    }

    private void doStart(File baseDir) throws LifecycleException {
        logger.info("Starting Embedded Tomcat...");
        // resolving paths
        Paths paths = new Paths(baseDir, fsProps.getConfDir(), fsProps.getWorkDir(), generalProps.getDocBaseDir(), fsProps.getWebXmlPath(), sslProps.getKeystoreFile(), sslProps.getTruststoreFile(), sslProps.getCrlFile());
        logger.debug("Etomcat paths resolved: " + paths);
        // creating
        embedded = new Embedded();
        executor = createExecutor();
        StandardEngine engine = createEngine(paths);
        Host host = createHost(paths);
        StandardContext context = createContext(paths);
        Connector connector = createConnector(paths);
        // tomcat binding
        embedded.addEngine(engine);
        engine.setDefaultHost(host.getName());
        engine.addChild(host);
        host.addChild(context);
        Http11NioProtocol proto = (Http11NioProtocol) connector.getProtocolHandler();
        proto.setExecutor(executor);
        embedded.addConnector(connector);
        // spring binding
        EmbeddedSpringContext embeddedSpringContext = (EmbeddedSpringContext) springContext;
        embeddedSpringContext.bind(context.getServletContext());
        // starting
        executor.start();
        embedded.start();
        started = true;
        logger.info("Embedded Tomcat started successfully");
    }

    private void doStop() {
        logger.info("Stopping Embedded Tomcat...");
        try {
            embedded.stop();
        } catch (Exception e) {
            logger.warn("Exception occured on stopping etomcat", e);
        }
        try {
            executor.stop();
        } catch (Exception e) {
            logger.warn("Exception occured on stopping etomcat executor", e);
        }
        logger.info("Embedded Tomcat stopped");
    }

    private StandardEngine createEngine(Paths paths) {
        StandardEngine engine = new StandardEngine();
        engine.setName(getClass().getName());
        engine.setBaseDir(paths.getBaseDir());
        return engine;
    }

    private StandardHost createHost(Paths paths) {
        StandardHost host = new StandardHost();
        host.setAutoDeploy(false);
        host.setDeployOnStartup(false);
        host.setDeployXML(false);
        host.setUnpackWARs(false);

        host.setAppBase("NOT_SUPPORTED_SETTING");
        host.setName(hostProps.getName());
        host.setErrorReportValveClass(hostProps.getErrorReportValveClass());
        host.setWorkDir(paths.getWorkDir());
        return host;
    }

    private StandardContext createContext(Paths paths) {
        StandardContext context = new StandardContext();
        context.setAltDDName(paths.getWebXmlFile());
        context.setCrossContext(true);
        context.setPrivileged(true);
        context.setClearReferencesHttpClientKeepAliveThread(false);
        context.setProcessTlds(false);
        context.setUnpackWAR(false);
        context.setUseNaming(false);
        context.setConfigured(true);
        context.setIgnoreAnnotations(true);
        context.setWrapperClass(EmbeddedWrapper.class.getName());

        context.setPath(generalProps.getContextPath());
        context.setCookies(contextProps.isCookies());
        context.setDisableURLRewriting(contextProps.isDisableURLRewriting());
        context.setDocBase(paths.getDocBaseDir());
        context.setCacheMaxSize(contextProps.getCacheMaxSizeKb());
        context.setCacheObjectMaxSize(contextProps.getCacheObjectMaxSizeKb());
        context.setCacheTTL(contextProps.getCacheTTLSec());
        context.setCachingAllowed(contextProps.isCachingAllowed());
        context.setUnloadDelay(contextProps.getUnloadDelayMs());
        context.setSessionTimeout(contextProps.getSessionTimeoutMinutes());

        if(contextProps.getPostCharacterEncoding().length() > 0) {
            logger.debug("UTF-8 encoding enabled for all requests");
            Valve valve = new PostCharacterEncodingValve(contextProps.getPostCharacterEncoding());
            context.addValve(valve);
        }

        context.setLoader(new EmbeddedLoader());
        if(generalProps.isUseFsResources()) {
            logger.debug("Using FileDirContext");
            context.setResources(new FileDirContext());
        } else {
            logger.debug("Using EmbeddedDirContext");
            context.setResources(new EmbeddedDirContext());
        }
        context.setManager(createManager());

        context.addLifecycleListener(new EmbeddedContextConfig(paths.getWebXmlFile()));
        return context;
    }

    private EmbeddedManager createManager() {
        EmbeddedManager manager = new EmbeddedManager();
        manager.setMaxActiveSessions(contextProps.getMaxActiveSessions());
        manager.setMaxInactiveInterval(contextProps.getSessionTimeoutMinutes() * 60);
        return manager;
    }

    private Connector createConnector(Paths paths) {
        Connector con = null;
        try {
            con = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Http11NioProtocol proto = (Http11NioProtocol) con.getProtocolHandler();
        con.setEnableLookups(connectorProps.isEnableLookups());
        con.setMaxPostSize(connectorProps.getMaxPostSizeBytes());
        con.setURIEncoding(connectorProps.getUriEncoding());
        con.setProperty("acceptCount", Integer.toString(connectorProps.getAcceptCount()));
        proto.setCompressableMimeType(connectorProps.getCompressableMimeType());
        proto.setCompression(connectorProps.getCompression());
        proto.setCompressionMinSize(connectorProps.getCompressionMinSizeBytes());
        if(!connectorProps.getNoCompressionUserAgents().isEmpty()) proto.setNoCompressionUserAgents(connectorProps.getNoCompressionUserAgents());
        proto.setDisableUploadTimeout(connectorProps.isDisableUploadTimeout());
        proto.setMaxHttpHeaderSize(connectorProps.getMaxHttpHeaderSizeBytes());
        proto.setMaxKeepAliveRequests(connectorProps.getMaxKeepAliveRequests());
        con.setPort(generalProps.getPort());
        proto.setServer(connectorProps.getServer());
        proto.setSocketBuffer(connectorProps.getSocketBufferBytes());

        proto.setUseSendfile(nioProps.isUseSendfile());
        con.setProperty("acceptorThreadCount", Integer.toString(nioProps.getAcceptorThreadCount()));
        proto.setAcceptorThreadPriority(nioProps.getAcceptorThreadPriority());
        proto.setPollerThreadCount(nioProps.getPollerThreadCount());
        proto.setPollerThreadPriority(nioProps.getPollerThreadPriority());
        proto.setSelectorTimeout(nioProps.getSelectorTimeoutMs());
        proto.setOomParachute(nioProps.getOomParachute());
        proto.setUseExecutor(true);
        con.setProperty("useComet", Boolean.toString(connectorProps.isUseComet()));
        // made all socket options lazy to prevent crash on winxp
        if(socketProps.isDirectBuffer()) con.setProperty("socket.directBuffer", Boolean.toString(true));
        if(25188 != socketProps.getRxBufSizeBytes()) con.setProperty("socket.rxBufSize", Integer.toString(socketProps.getRxBufSizeBytes()));
        if(43800 != socketProps.getTxBufSizeBytes()) con.setProperty("socket.txBufSize", Integer.toString(socketProps.getTxBufSizeBytes()));
        if(8192 != socketProps.getAppReadBufSizeBytes()) con.setProperty("socket.appReadBufSize", Integer.toString(socketProps.getAppReadBufSizeBytes()));
        if(8192 != socketProps.getAppWriteBufSizeBytes()) con.setProperty("socket.appWriteBufSize", Integer.toString(socketProps.getAppWriteBufSizeBytes()));
        if(500 != socketProps.getBufferPool()) con.setProperty("socket.bufferPool", Integer.toString(socketProps.getBufferPool()));
        if(104857600 != socketProps.getBufferPoolSizeBytes()) con.setProperty("socket.bufferPoolSize", Integer.toString(socketProps.getBufferPoolSizeBytes()));
        if(500 != socketProps.getProcessorCache())con.setProperty("socket.processorCache", Integer.toString(socketProps.getProcessorCache()));
        if(500 != socketProps.getKeyCache()) con.setProperty("socket.keyCache", Integer.toString(socketProps.getKeyCache()));
        if(500 != socketProps.getEventCache()) con.setProperty("socket.eventCache", Integer.toString(socketProps.getEventCache()));
        if(socketProps.isTcpNoDelay()) con.setProperty("socket.tcpNoDelay", Boolean.toString(true));
        if(socketProps.isSoKeepAlive()) con.setProperty("socket.soKeepAlive", Boolean.toString(true));
        if(!socketProps.isOoBInline()) con.setProperty("socket.ooBInline", Boolean.toString(false));
        if(!socketProps.isSoReuseAddress()) con.setProperty("socket.soReuseAddress", Boolean.toString(false));
        if(!socketProps.isSoLingerOn()) con.setProperty("socket.soLingerOn", Boolean.toString(false));
        if(25 != socketProps.getSoLingerTimeSec())con.setProperty("socket.soLingerTime", Integer.toString(socketProps.getSoLingerTimeSec()));
        if(5000 != socketProps.getSoTimeoutMs()) con.setProperty("socket.soTimeout", Integer.toString(socketProps.getSoTimeoutMs()));
        if((0x04 | 0x08 | 0x010) != socketProps.getSoTrafficClass()) con.setProperty("socket.soTrafficClass", Integer.toString(socketProps.getSoTrafficClass()));
        if(1 != socketProps.getPerformanceConnectionTime()) con.setProperty("socket.performanceConnectionTime", Integer.toString(socketProps.getPerformanceConnectionTime()));
        if(0 != socketProps.getPerformanceLatency()) con.setProperty("socket.performanceLatency", Integer.toString(socketProps.getPerformanceLatency()));
        if(1 != socketProps.getPerformanceBandwidth()) con.setProperty("socket.performanceBandwidth", Integer.toString(socketProps.getPerformanceBandwidth()));
        if(250 != socketProps.getUnlockTimeoutMs())con.setProperty("socket.unlockTimeout", Integer.toString(socketProps.getUnlockTimeoutMs()));
        con.setProperty("selectorPool.maxSelectors", Integer.toString(nioProps.getMaxSelectors()));
        con.setProperty("selectorPool.maxSpareSelectors", Integer.toString(nioProps.getMaxSpareSelectors()));

        if(sslProps.isSslEnabled()) {
            proto.setSSLEnabled(true);
            con.setScheme("https");
            con.setSecure(true);
            proto.setKeystoreFile(paths.getKeystoreFile());
            proto.setKeystorePass(sslProps.getKeystorePass());
            proto.setKeystoreType(sslProps.getKeystoreType());
            proto.setProperty("keystoreProvider", sslProps.getKeystoreProvider());
            proto.setKeyAlias(sslProps.getKeyAlias());
            proto.setAlgorithm(sslProps.getAlgorithm());
            if(sslProps.isClientAuth()) {
                proto.setClientAuth("true");
                proto.setTruststoreFile(paths.getTruststoreFile());
                proto.setTruststorePass(sslProps.getTruststorePass());
                proto.setTruststoreType(sslProps.getTruststoreType());
                con.setProperty("truststoreProvider", sslProps.getTruststoreProvider());
            }
            proto.setSslProtocol(sslProps.getProtocol());
            proto.setCiphers(sslProps.getCiphers());
            con.setProperty("sessionCacheSize", Integer.toString(sslProps.getSessionCacheSize()));
            con.setProperty("sessionTimeout", Integer.toString(sslProps.getSessionTimeoutSecs()));
            if(!sslProps.getCrlFile().isEmpty()) con.setProperty("crlFile", paths.getCrlFile());
        }

        return con;
    }

    private Executor createExecutor() {
        StandardThreadExecutor executor = new StandardThreadExecutor();
        executor.setThreadPriority(executorProps.getThreadPriority());
		executor.setName(executorProps.getName());
		executor.setDaemon(executorProps.isDaemon());
		executor.setNamePrefix(executorProps.getNamePrefix());
		executor.setMaxThreads(executorProps.getMaxThreads());
		executor.setMinSpareThreads(executorProps.getMinSpareThreads());
		executor.setMaxIdleTime(executorProps.getMaxIdleTimeMs());
		return executor;
    }

    public ExecutorState getInnerExecutorState() {
        StandardThreadExecutor executorImpl = (StandardThreadExecutor)executor;
        return new ExecutorState(executorImpl.getMaxIdleTime(),
                executorImpl.getMaxThreads(),
                executorImpl.getMinSpareThreads(),
                executorImpl.getActiveCount(),
                executorImpl.getCompletedTaskCount(),
                executorImpl.getCorePoolSize(),
                executorImpl.getLargestPoolSize(),
                executorImpl.getPoolSize(),
                executorImpl.getQueueSize());
    }

    class Paths {
        private final String baseDir;
        private final String confDir;
        private final String workDir;
        private final String docBaseDir;
        private final String webXmlFile;
        private final String keystoreFile;
        private final String truststoreFile;
        private final String crlFile;

        private Paths(File baseDir, String confDir, String workDir, String docBaseDir, String webXmlFile, String keystoreFile, String truststoreFile, String crlFile) {
            this.baseDir = baseDir.getAbsolutePath();
            this.confDir = this.baseDir + separator + confDir;
            this.workDir = this.baseDir + separator + workDir;
            this.docBaseDir = this.baseDir + separator + docBaseDir;
            this.webXmlFile = this.confDir + separator + webXmlFile;
            this.keystoreFile = this.confDir + separator + keystoreFile;
            this.truststoreFile = this.confDir + separator + truststoreFile;
            this.crlFile = this.confDir + separator + crlFile;
        }

        public String getBaseDir() {
            return baseDir;
        }

        public String getConfDir() {
            return confDir;
        }

        public String getWorkDir() {
            return workDir;
        }

        public String getDocBaseDir() {
            return docBaseDir;
        }

        public String getWebXmlFile() {
            return webXmlFile;
        }

        public String getKeystoreFile() {
            return keystoreFile;
        }

        public String getTruststoreFile() {
            return truststoreFile;
        }

        public String getCrlFile() {
            return crlFile;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("Paths");
            sb.append("{baseDir='").append(baseDir).append('\'');
            sb.append(", confDir='").append(confDir).append('\'');
            sb.append(", workDir='").append(workDir).append('\'');
            sb.append(", docBaseDir='").append(docBaseDir).append('\'');
            sb.append(", webXmlFile='").append(webXmlFile).append('\'');
            sb.append(", keystoreFile='").append(keystoreFile).append('\'');
            sb.append(", truststoreFile='").append(truststoreFile).append('\'');
            sb.append(", crlFile='").append(crlFile).append('\'');
            sb.append('}');
            return sb.toString();
        }
    }

    // setters for spring
    @Override
    public void setApplicationContext(ApplicationContext springContext) {
        this.springContext = springContext;
    }

    public EmbeddedTomcat setGeneralProps(GeneralProperties generalProps) {
        this.generalProps = generalProps;
        return this;
    }

    public EmbeddedTomcat setFsProps(FsProperties fsProps) {
        this.fsProps = fsProps;
        return this;
    }

    public EmbeddedTomcat setHostProps(HostProperties hostProps) {
        this.hostProps = hostProps;
        return this;
    }

    public EmbeddedTomcat setContextProps(ContextProperties contextProps) {
        this.contextProps = contextProps;
        return this;
    }

    public EmbeddedTomcat setConnectorProps(ConnectorProperties connectorProps) {
        this.connectorProps = connectorProps;
        return this;
    }

    public EmbeddedTomcat setNioProps(NioProperties nioProps) {
        this.nioProps = nioProps;
        return this;
    }

    public EmbeddedTomcat setSocketProps(SocketProperties socketProps) {
        this.socketProps = socketProps;
        return this;
    }

    public EmbeddedTomcat setSslProps(SslProperties sslProps) {
        this.sslProps = sslProps;
        return this;
    }

    public EmbeddedTomcat setExecutorProps(ExecutorProperties executorProps) {
        this.executorProps = executorProps;
        return this;
    }
}
