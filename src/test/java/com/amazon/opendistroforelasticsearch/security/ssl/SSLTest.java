/*
 * Copyright 2015-2018 _floragunn_ GmbH
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Portions Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.security.ssl;

import io.netty.util.internal.PlatformDependent;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;

import org.apache.http.NoHttpResponseException;
import org.apache.lucene.util.Constants;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.PluginAwareNode;
import org.elasticsearch.transport.Netty4Plugin;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.amazon.opendistroforelasticsearch.security.ssl.DefaultOpenDistroSecurityKeyStore;
import com.amazon.opendistroforelasticsearch.security.ssl.ExternalOpenDistroSecurityKeyStore;
import com.amazon.opendistroforelasticsearch.security.ssl.OpenDistroSecuritySSLPlugin;
import com.amazon.opendistroforelasticsearch.security.ssl.util.ExceptionUtils;
import com.amazon.opendistroforelasticsearch.security.ssl.util.SSLConfigConstants;

@SuppressWarnings({"resource", "unchecked"})
public class SSLTest extends AbstractUnitTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    protected boolean allowOpenSSL = false;
    
    @Test
    public void testHttps() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;
        keystore = "node-untspec5-keystore.p12";

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", false)
                .put("opendistro_security.ssl.http.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put("opendistro_security.ssl.http.clientauth_mode", "REQUIRE")
                .putList(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLED_PROTOCOLS, "TLSv1.1","TLSv1.2")
                .putList(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLED_CIPHERS, "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256")
                .putList(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLED_PROTOCOLS, "TLSv1.1","TLSv1.2")
                .putList(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLED_CIPHERS, "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256")
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .build();

        startES(settings);

        System.out.println(executeSimpleRequest("_opendistro/_security/sslinfo?pretty&show_dn=true"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty&show_dn=true").contains("EMAILADDRESS=unt@tst.com"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty&show_dn=true").contains("local_certificates_list"));
        Assert.assertFalse(executeSimpleRequest("_opendistro/_security/sslinfo?pretty&show_dn=false").contains("local_certificates_list"));
        Assert.assertFalse(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("local_certificates_list"));
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertFalse(executeSimpleRequest("_nodes/settings?pretty").contains("\"opendistro_security\""));
        Assert.assertFalse(executeSimpleRequest("_nodes/settings?pretty").contains("keystore_filepath"));
        //Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));

    }
    
    @Test
    public void testCipherAndProtocols() throws Exception {
        
        Security.setProperty("jdk.tls.disabledAlgorithms","");
        System.out.println("Disabled algos: "+Security.getProperty("jdk.tls.disabledAlgorithms"));
        System.out.println("allowOpenSSL: "+allowOpenSSL);

        Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", false)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("opendistro_security.ssl.http.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put("opendistro_security.ssl.http.clientauth_mode", "REQUIRE")
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                 //WEAK and insecure cipher, do NOT use this, its here for unittesting only!!!
                .put("opendistro_security.ssl.http.enabled_ciphers","SSL_RSA_EXPORT_WITH_RC4_40_MD5")
                 //WEAK and insecure protocol, do NOT use this, its here for unittesting only!!!
                .put("opendistro_security.ssl.http.enabled_protocols","SSLv3")
                .put("client.type","node")
                .put("path.home",".")
                .build();
        
        try {
            String[] enabledCiphers = new DefaultOpenDistroSecurityKeyStore(settings, Paths.get(".")).createHTTPSSLEngine().getEnabledCipherSuites();
            String[] enabledProtocols = new DefaultOpenDistroSecurityKeyStore(settings, Paths.get(".")).createHTTPSSLEngine().getEnabledProtocols();

            if(allowOpenSSL) {
                Assert.assertEquals(2, enabledProtocols.length); //SSLv2Hello is always enabled when using openssl
                Assert.assertTrue("Check SSLv3", "SSLv3".equals(enabledProtocols[0]) || "SSLv3".equals(enabledProtocols[1]));
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("TLS_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            } else {
                Assert.assertEquals(1, enabledProtocols.length);
                Assert.assertEquals("SSLv3", enabledProtocols[0]);
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("SSL_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            }
            
            settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                    .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                    .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                    .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                    .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                     //WEAK and insecure cipher, do NOT use this, its here for unittesting only!!!
                    .put("opendistro_security.ssl.transport.enabled_ciphers","SSL_RSA_EXPORT_WITH_RC4_40_MD5")
                     //WEAK and insecure protocol, do NOT use this, its here for unittesting only!!!
                    .put("opendistro_security.ssl.transport.enabled_protocols","SSLv3")
                    .put("client.type","node")
                    .put("path.home",".")
                    .build();
            
            enabledCiphers = new DefaultOpenDistroSecurityKeyStore(settings, Paths.get(".")).createServerTransportSSLEngine().getEnabledCipherSuites();
            enabledProtocols = new DefaultOpenDistroSecurityKeyStore(settings, Paths.get(".")).createServerTransportSSLEngine().getEnabledProtocols();

            if(allowOpenSSL) {
                Assert.assertEquals(2, enabledProtocols.length); //SSLv2Hello is always enabled when using openssl
                Assert.assertTrue("Check SSLv3", "SSLv3".equals(enabledProtocols[0]) || "SSLv3".equals(enabledProtocols[1]));
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("TLS_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            } else {
                Assert.assertEquals(1, enabledProtocols.length);
                Assert.assertEquals("SSLv3", enabledProtocols[0]);
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("SSL_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            }
            enabledCiphers = new DefaultOpenDistroSecurityKeyStore(settings, Paths.get(".")).createClientTransportSSLEngine(null, -1).getEnabledCipherSuites();
            enabledProtocols = new DefaultOpenDistroSecurityKeyStore(settings, Paths.get(".")).createClientTransportSSLEngine(null, -1).getEnabledProtocols();

            if(allowOpenSSL) {
                Assert.assertEquals(2, enabledProtocols.length); //SSLv2Hello is always enabled when using openssl
                Assert.assertTrue("Check SSLv3","SSLv3".equals(enabledProtocols[0]) || "SSLv3".equals(enabledProtocols[1]));
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("TLS_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            } else {
                Assert.assertEquals(1, enabledProtocols.length);
                Assert.assertEquals("SSLv3", enabledProtocols[0]);
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("SSL_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            }
        } catch (ElasticsearchSecurityException e) {
            System.out.println("EXPECTED "+e.getClass().getSimpleName()+" for "+System.getProperty("java.specification.version")+": "+e.toString());
            e.printStackTrace();
            Assert.assertTrue("Check if error contains 'no valid cipher suites' -> "+e.toString(),e.toString().contains("no valid cipher suites")
                    || e.toString().contains("failed to set cipher suite")
                    || e.toString().contains("Unable to configure permitted SSL ciphers")
                    || e.toString().contains("OPENSSL_internal:NO_CIPHER_MATCH")
                   );
            Assert.assertTrue("Check if >= Java 8 and no openssl",allowOpenSSL?true:Constants.JRE_IS_MINIMUM_JAVA8 );        
        }
    }
    
    @Test
    public void testHttpsOptionalAuth() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", false)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("opendistro_security.ssl.http.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);

        System.out.println(executeSimpleRequest("_opendistro/_security/sslinfo?pretty"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("TLS"));
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertFalse(executeSimpleRequest("_nodes/settings?pretty").contains("\"opendistro_security\""));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }
    
    @Test
    public void testHttpsAndNodeSSL() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0")
                .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false)

                .put("opendistro_security.ssl.http.enabled", true).put("opendistro_security.ssl.http.clientauth_mode", "REQUIRE")
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                
                .build();

        startES(settings);
        System.out.println(executeSimpleRequest("_opendistro/_security/sslinfo?pretty"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("TLS"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"tx_size_in_bytes\" : 0"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"rx_count\" : 0"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"rx_size_in_bytes\" : 0"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"tx_count\" : 0"));
    
    }
    
    @Test
    public void testHttpsAndNodeSSLPem() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.crt.pem"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.key.pem"))
                //.put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false)

                .put("opendistro_security.ssl.http.enabled", true)
                .put("opendistro_security.ssl.http.clientauth_mode", "REQUIRE")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.crt.pem"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.key.pem"))
                //.put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .build();

        startES(settings);
        System.out.println(executeSimpleRequest("_opendistro/_security/sslinfo?pretty"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("TLS"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        //Assert.assertTrue(!executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("null"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }

    @Test
    public void testHttpsAndNodeSSLPemEnc() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("pem/node-4.crt.pem"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("pem/node-4.key"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false)

                .put("opendistro_security.ssl.http.enabled", true)
                .put("opendistro_security.ssl.http.clientauth_mode", "REQUIRE")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("pem/node-4.crt.pem"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("pem/node-4.key"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .build();

        startES(settings);
        System.out.println(executeSimpleRequest("_opendistro/_security/sslinfo?pretty"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("TLS"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        //Assert.assertTrue(!executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("null"));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }

    
    @Test
    public void testHttpsAndNodeSSLFailedCipher() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0")
                .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false)

                .put("opendistro_security.ssl.http.enabled", true).put("opendistro_security.ssl.http.clientauth_mode", "REQUIRE")
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
      
                .put("opendistro_security.ssl.transport.enabled_ciphers","INVALID_CIPHER")
                
                .build();

        try {
            startES(settings);
            Assert.fail();
        } catch (Exception e1) {
            Throwable e = ExceptionUtils.getRootCause(e1);
            if(allowOpenSSL) {
                Assert.assertTrue(e.toString(), e.toString().contains("no cipher match"));
            } else {
                Assert.assertTrue(e.toString(), e.toString().contains("no valid cipher"));
            }
        }
    }

    @Test
    public void testHttpPlainFail() throws Exception {
        thrown.expect(NoHttpResponseException.class);

        enableHTTPClientSSL = false;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = false;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", false)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("opendistro_security.ssl.http.enabled", true)
                .put("opendistro_security.ssl.http.clientauth_mode", "OPTIONAL")
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }

    @Test
    public void testHttpsNoEnforce() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = false;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", false)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("opendistro_security.ssl.http.enabled", true)
                .put("opendistro_security.ssl.http.clientauth_mode", "NONE")
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertFalse(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }
    
    @Test
    public void testHttpsEnforceFail() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = false;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", false)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("opendistro_security.ssl.http.enabled", true)
                .put("opendistro_security.ssl.http.clientauth_mode", "REQUIRE")
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        try {
            executeSimpleRequest("");
            Assert.fail();
        } catch (SocketException | SSLException e) {
            //expected
            System.out.println("Expected SSLHandshakeException "+e.toString());
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail("Unexpected exception "+e.toString());
        }
    }

    @Test
    public void testHttpsV3Fail() throws Exception {
        thrown.expect(SSLHandshakeException.class);

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = false;
        enableHTTPClientSSLv3Only = true;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", false)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("opendistro_security.ssl.http.enabled", true)
                .put("opendistro_security.ssl.http.clientauth_mode", "NONE")
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
    }

    // transport
    @Test
    public void testTransportClientSSL() throws Exception {

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false).build();

        startES(settings);
        
        log.debug("Elasticsearch started");

        final Settings tcSettings = Settings.builder().put("cluster.name", clustername).put(settings).build();

        try (TransportClient tc = new TransportClientImpl(tcSettings, asCollection(OpenDistroSecuritySSLPlugin.class))) {
            
            log.debug("TransportClient built, connect now to {}:{}", nodeHost, nodePort);
            
            tc.addTransportAddress(new TransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());            
            log.debug("TransportClient connected");           
            Assert.assertEquals("test", tc.index(new IndexRequest("test","test").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"a\":5}", XContentType.JSON)).actionGet().getIndex());            
            log.debug("Index created");           
            Assert.assertEquals(1L, tc.search(new SearchRequest("test")).actionGet().getHits().getTotalHits());
            log.debug("Search done");
            Assert.assertEquals(3, tc.admin().cluster().health(new ClusterHealthRequest("test")).actionGet().getNumberOfNodes());
            log.debug("ClusterHealth done");            
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());           
            log.debug("NodesInfoRequest asserted");
        }
    }

    @Test
    public void testTransportClientSSLExternalContext() throws Exception {

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false).build();

        startES(settings);
        
        log.debug("Elasticsearch started");

        final Settings tcSettings = Settings.builder()
                .put("cluster.name", clustername)
                .put("path.home", ".")
                .put("opendistro_security.ssl.client.external_context_id", "abcx")
                .build();

        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory
                .getDefaultAlgorithm());
        final KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(this.getClass().getResourceAsStream("/truststore.jks"), "changeit".toCharArray());
        tmf.init(trustStore);

        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory
                .getDefaultAlgorithm());
        final KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(this.getClass().getResourceAsStream("/node-0-keystore.jks"), "changeit".toCharArray());        
        kmf.init(keyStore, "changeit".toCharArray());
        
        
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        ExternalOpenDistroSecurityKeyStore.registerExternalSslContext("abcx", sslContext);
        
        try (TransportClient tc = new TransportClientImpl(tcSettings, asCollection(OpenDistroSecuritySSLPlugin.class))) {
            
            log.debug("TransportClient built, connect now to {}:{}", nodeHost, nodePort);
            
            tc.addTransportAddress(new TransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            
            log.debug("TransportClient connected");
            
            Assert.assertEquals("test", tc.index(new IndexRequest("test","test").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"a\":5}", XContentType.JSON)).actionGet().getIndex());
            
            log.debug("Index created");
            
            Assert.assertEquals(1L, tc.search(new SearchRequest("test")).actionGet().getHits().getTotalHits());

            log.debug("Search done");
            
            Assert.assertEquals(3, tc.admin().cluster().health(new ClusterHealthRequest("test")).actionGet().getNumberOfNodes());

            log.debug("ClusterHealth done");
            
            //Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().length);
            
            //log.debug("NodesInfoRequest asserted");
            
        }
    }

    @Test
    public void testNodeClientSSL() throws Exception {

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false)
                .build();

        startES(settings);      

        final Settings tcSettings = Settings.builder().put("cluster.name", clustername).put("path.home", ".")
                .put("node.name", "client_node_" + new Random().nextInt())
                .put(settings)// -----
                .build();

        try (Node node = new PluginAwareNode(tcSettings, Netty4Plugin.class, OpenDistroSecuritySSLPlugin.class).start()) {
            ClusterHealthResponse res = node.client().admin().cluster().health(new ClusterHealthRequest().waitForNodes("4").timeout(TimeValue.timeValueSeconds(5))).actionGet();
            Assert.assertFalse(res.isTimedOut());
            Assert.assertEquals(4, res.getNumberOfNodes());
            Assert.assertEquals(4, node.client().admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
        }

        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"tx_size_in_bytes\" : 0"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"rx_count\" : 0"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"rx_size_in_bytes\" : 0"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"tx_count\" : 0"));
    }

    @Test
    public void testTransportClientSSLFail() throws Exception {
        thrown.expect(NoNodeAvailableException.class);

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false).build();

        startES(settings);

        final Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put("path.home", getAbsoluteFilePathFromClassPath("node-0-keystore.jks").getParent())
                .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore_fail.jks"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false).build();

        try (TransportClient tc = new TransportClientImpl(tcSettings, asCollection(OpenDistroSecuritySSLPlugin.class))) {
            tc.addTransportAddress(new TransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
        }
    }

    @Test
    public void testAvailCiphers() throws Exception {
        final SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(null, null, null);
        final SSLEngine engine = serverContext.createSSLEngine();
        final List<String> jdkSupportedCiphers = new ArrayList<>(Arrays.asList(engine.getSupportedCipherSuites()));
        jdkSupportedCiphers.retainAll(SSLConfigConstants.getSecureSSLCiphers(Settings.EMPTY, false));
        engine.setEnabledCipherSuites(jdkSupportedCiphers.toArray(new String[0]));

        final List<String> jdkEnabledCiphers = Arrays.asList(engine.getEnabledCipherSuites());
        // example
        // TLS_RSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        // TLS_RSA_WITH_AES_128_CBC_SHA, TLS_DHE_RSA_WITH_AES_128_CBC_SHA
        System.out.println("JDK enabled ciphers: " + jdkEnabledCiphers);
        Assert.assertTrue(jdkEnabledCiphers.size() > 0);
    }
    
    @Test
    public void testUnmodifieableCipherProtocolConfig() throws Exception {
        SSLConfigConstants.getSecureSSLProtocols(Settings.EMPTY, false)[0] = "bogus";
        Assert.assertEquals("TLSv1.3", SSLConfigConstants.getSecureSSLProtocols(Settings.EMPTY, false)[0]);
        
        try {
            SSLConfigConstants.getSecureSSLCiphers(Settings.EMPTY, false).set(0, "bogus");
            Assert.fail();
        } catch (UnsupportedOperationException e) {
            //expected
        }
    }
    
    @Test
    public void testCustomPrincipalExtractor() throws Exception {
        
        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;
        
        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false)
                .put("opendistro_security.ssl.transport.principal_extractor_class", "com.amazon.opendistroforelasticsearch.security.ssl.TestPrincipalExtractor")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0")
                .put("opendistro_security.ssl.http.enabled", true)
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        
        log.debug("Elasticsearch started");

        final Settings tcSettings = Settings.builder().put("cluster.name", clustername).put("path.home", ".").put(settings).build();

        try (TransportClient tc = new TransportClientImpl(tcSettings, asCollection(OpenDistroSecuritySSLPlugin.class))) {
            
            log.debug("TransportClient built, connect now to {}:{}", nodeHost, nodePort);
            
            tc.addTransportAddress(new TransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());            
            log.debug("TransportClient connected");
            TestPrincipalExtractor.reset();
            Assert.assertEquals("test", tc.index(new IndexRequest("test","test").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"a\":5}", XContentType.JSON)).actionGet().getIndex());            
            log.debug("Index created");           
            Assert.assertEquals(1L, tc.search(new SearchRequest("test")).actionGet().getHits().getTotalHits());
            log.debug("Search done");
            Assert.assertEquals(3, tc.admin().cluster().health(new ClusterHealthRequest("test")).actionGet().getNumberOfNodes());
            log.debug("ClusterHealth done");            
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());           
            log.debug("NodesInfoRequest asserted");
        }
        
        executeSimpleRequest("_opendistro/_security/sslinfo?pretty");
        
        //we need to test this in Security itself because in the SSL only plugin the info is not longer propagated
        //Assert.assertTrue(TestPrincipalExtractor.getTransportCount() > 0);
        Assert.assertTrue(TestPrincipalExtractor.getHttpCount() > 0);
    }

    @Test
    public void testCRLPem() throws Exception {
        
        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.crt.pem"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.key.pem"))
                //.put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false)

                .put("opendistro_security.ssl.http.enabled", true)
                .put("opendistro_security.ssl.http.clientauth_mode", "REQUIRE")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.crt.pem"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.key.pem"))
                //.put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("chain-ca.pem"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_CRL_VALIDATE, true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_CRL_VALIDATION_DATE, CertificateValidatorTest.CRL_DATE.getTime())
                .build();

        startES(settings);
        Assert.assertTrue(executeSimpleRequest("_opendistro/_security/sslinfo?pretty").contains("TLS"));
    }
    
    @Test
    public void testCRL() throws Exception {
        
        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", false)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("opendistro_security.ssl.http.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put("opendistro_security.ssl.http.clientauth_mode", "REQUIRE")
                .put("opendistro_security.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_CRL_VALIDATE, true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_CRL_FILE, getAbsoluteFilePathFromClassPath("crl/revoked.crl"))
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_CRL_VALIDATION_DATE, CertificateValidatorTest.CRL_DATE.getTime())
                .build();

        startES(settings);

        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        
    }
    
    @Test
    public void testNodeClientSSLwithJavaTLSv13() throws Exception {
        
        //Java TLS 1.3 is available since Java 11
        Assume.assumeTrue(!allowOpenSSL && PlatformDependent.javaVersion() >= 11);

        final Settings settings = Settings.builder().put("opendistro_security.ssl.transport.enabled", true)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("opendistro_security.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("opendistro_security.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("opendistro_security.ssl.transport.enforce_hostname_verification", false)
                .put("opendistro_security.ssl.transport.resolve_hostname", false)
                .putList(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLED_PROTOCOLS, "TLSv1.3")
                .putList(SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENABLED_CIPHERS, "TLS_AES_128_GCM_SHA256")
                .build();

        startES(settings);      

        final Settings tcSettings = Settings.builder().put("cluster.name", clustername).put("path.home", ".")
                .put("node.name", "client_node_" + new Random().nextInt())
                .put(settings)// -----
                .build();

        try (Node node = new PluginAwareNode(tcSettings, Netty4Plugin.class, OpenDistroSecuritySSLPlugin.class).start()) {
            ClusterHealthResponse res = node.client().admin().cluster().health(new ClusterHealthRequest().waitForNodes("4").timeout(TimeValue.timeValueSeconds(5))).actionGet();
            Assert.assertFalse(res.isTimedOut());
            Assert.assertEquals(4, res.getNumberOfNodes());
            Assert.assertEquals(4, node.client().admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
        }

        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"tx_size_in_bytes\" : 0"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"rx_count\" : 0"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"rx_size_in_bytes\" : 0"));
        Assert.assertFalse(executeSimpleRequest("_nodes/stats?pretty").contains("\"tx_count\" : 0"));
    }
}
