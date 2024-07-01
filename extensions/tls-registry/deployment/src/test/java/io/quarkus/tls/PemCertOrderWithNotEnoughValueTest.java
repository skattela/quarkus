package io.quarkus.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.security.KeyStoreException;
import java.security.cert.CertificateParsingException;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import me.escoffier.certs.Format;
import me.escoffier.certs.junit5.Certificate;
import me.escoffier.certs.junit5.Certificates;

@Certificates(baseDir = "target/certs", certificates = {
        @Certificate(name = "test-formats", password = "password", formats = { Format.JKS, Format.PEM, Format.PKCS12 })
})
public class PemCertOrderWithNotEnoughValueTest {

    private static final String configuration = """
            quarkus.tls.key-store.pem.foo.cert=target/certs/test-format.crt
            quarkus.tls.key-store.pem.foo.key=target/certs/test-formats.key

            quarkus.tls.key-store.pem.bar.cert=target/certs/test-format.crt
            quarkus.tls.key-store.pem.bar.key=target/certs/test-formats.key

            quarkus.tls.key-store.pem.order=bar
            """;

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest().setArchiveProducer(
            () -> ShrinkWrap.create(JavaArchive.class)
                    .add(new StringAsset(configuration), "application.properties"))
            .assertException(t -> assertThat(t.getCause().getMessage()).contains("`order`", "keyCerts`"));

    @Test
    void test() throws KeyStoreException, CertificateParsingException {
        fail("Should not be called as the extension should fail before.");
    }
}
