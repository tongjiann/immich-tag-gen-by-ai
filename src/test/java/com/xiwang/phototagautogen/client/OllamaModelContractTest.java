package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiwang.phototagautogen.config.OllamaProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.ImageAnalysis;
import com.xiwang.phototagautogen.domain.Taxonomy;
import com.xiwang.phototagautogen.service.TaxonomyLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_OLLAMA_CONTRACT_TEST", matches = "true")
class OllamaModelContractTest {

    @Test
    void 本地模型对典型样本返回合法受控结构() throws Exception {
        ProcessingProperties processingProperties = new ProcessingProperties();
        processingProperties.setModelTimeoutSeconds(300);
        OllamaHttpClient client = new OllamaHttpClient(new OllamaProperties(), processingProperties,
                new ObjectMapper().findAndRegisterModules());
        Taxonomy taxonomy = new TaxonomyLoader().load();

        client.validateConnection();
        for (byte[] sample : List.of(landscape(), portrait(), objectOnly(), lowEvidence())) {
            ImageAnalysis analysis = client.analyze(sample, taxonomy);
            assertThat(analysis.description()).isNotBlank();
            assertThat(analysis.tags()).hasSizeLessThanOrEqualTo(15);
            assertThat(analysis.tags()).allSatisfy(tag -> {
                assertThat(tag.confidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
                assertThat(taxonomy.isAllowed(tag.tagPath())).isTrue();
            });
        }
    }

    private byte[] landscape() throws Exception {
        BufferedImage image = canvas(new Color(120, 190, 240));
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(250, 190, 55));
        graphics.fillOval(210, 20, 35, 35);
        graphics.setColor(new Color(70, 115, 85));
        graphics.fillPolygon(new int[] {0, 85, 150}, new int[] {180, 65, 180}, 3);
        graphics.setColor(new Color(45, 90, 70));
        graphics.fillPolygon(new int[] {80, 180, 256}, new int[] {180, 80, 180}, 3);
        graphics.setColor(new Color(70, 150, 75));
        graphics.fillRect(0, 180, 256, 76);
        graphics.dispose();
        return png(image);
    }

    private byte[] portrait() throws Exception {
        BufferedImage image = canvas(new Color(225, 215, 200));
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(215, 170, 135));
        graphics.fillOval(90, 35, 76, 76);
        graphics.setColor(new Color(45, 55, 70));
        graphics.fillOval(82, 28, 92, 45);
        graphics.setColor(new Color(65, 110, 170));
        graphics.fillRoundRect(65, 108, 126, 140, 35, 35);
        graphics.dispose();
        return png(image);
    }

    private byte[] objectOnly() throws Exception {
        BufferedImage image = canvas(new Color(245, 240, 225));
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(120, 75, 45));
        graphics.fillRect(0, 185, 256, 71);
        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(82, 80, 88, 92, 12, 12);
        graphics.setColor(new Color(80, 80, 80));
        graphics.drawOval(145, 103, 48, 45);
        graphics.dispose();
        return png(image);
    }

    private byte[] lowEvidence() throws Exception {
        return png(canvas(new Color(145, 145, 145)));
    }

    private BufferedImage canvas(Color background) {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(background);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        return image;
    }

    private byte[] png(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
