package com.tencent.weknora.docreader;

import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.ModelURLPolicy;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

@Configuration
public class DocReaderConfiguration {
    @Bean(destroyMethod = "close")
    @Primary
    public RemoteDocumentReader docReaderClient(Environment environment, ModelEgressPolicy egress, ModelURLPolicy inputPolicy) throws IOException {
        String address = HttpDocumentReader.goTrim(environment.getProperty("weknora.docreader.address",
                environment.getProperty("DOCREADER_ADDR", "")));
        String transport = HttpDocumentReader.goTrim(environment.getProperty("DOCREADER_TRANSPORT", ""));
        transport = transport.toLowerCase(java.util.Locale.ROOT);
        if (transport.equals("http") || transport.equals("https")) {
            if (!address.isEmpty() && !address.startsWith("http://") && !address.startsWith("https://")) address = "http://" + address;
            return new HttpDocumentReader(address, egress, inputPolicy::validate);
        }
        return new DocReaderClient(DocReaderSettings.fromEnvironment(environment::getProperty).withAddress(address));
    }
}
