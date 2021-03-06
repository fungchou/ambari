/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ambari.infra.job.archive;

import org.apache.ambari.infra.job.ObjectSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.configuration.support.JobRegistryBeanPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.io.File;
import java.nio.file.Paths;

@Configuration
public class DocumentExportConfiguration {
  private static final Logger LOG = LoggerFactory.getLogger(DocumentExportConfiguration.class);

  @Inject
  private DocumentExportPropertyMap propertyMap;

  @Inject
  private StepBuilderFactory steps;

  @Inject
  private JobBuilderFactory jobs;

  @Inject
  @Qualifier("exportStep")
  private Step exportStep;

  @Inject
  private JobRegistryBeanPostProcessor jobRegistryBeanPostProcessor;


  @PostConstruct
  public void createJobs() {
    propertyMap.getSolrDataExport().values().forEach(DocumentExportProperties::validate);

    propertyMap.getSolrDataExport().keySet().forEach(jobName -> {
      Job job = logExportJob(jobName, exportStep);
      jobRegistryBeanPostProcessor.postProcessAfterInitialization(job, jobName);
    });
  }

  private Job logExportJob(String jobName, Step logExportStep) {
    return jobs.get(jobName).listener(new DocumentExportJobListener(propertyMap)).start(logExportStep).build();
  }

  @Bean
  @JobScope
  public Step exportStep(DocumentExporter documentExporter) {
    return steps.get("export")
            .tasklet(documentExporter)
            .build();
  }

  @Bean
  @StepScope
  public DocumentExporter documentExporter(DocumentItemReader documentItemReader,
                                           @Value("#{stepExecution.jobExecution.id}") String jobId,
                                           @Value("#{stepExecution.jobExecution.executionContext.get('exportProperties')}") DocumentExportProperties properties) {
    File path = Paths.get(
            properties.getDestinationDirectoryPath(),
            // TODO: jobId should remain the same after continuing job
            String.format("%s_%s", properties.getQuery().getCollection(), jobId)).toFile(); // TODO: add end date
    LOG.info("Destination directory path={}", path);
    if (!path.exists()) {
      if (!path.mkdirs()) {
        LOG.warn("Unable to create directory {}", path);
      }
    }

    CompositeFileAction fileAction = new CompositeFileAction(new TarGzCompressor());
    properties.s3Properties().ifPresent(s3Properties -> fileAction.add(new S3Uploader(s3Properties)));

    return new DocumentExporter(
            documentItemReader,
            firstDocument -> localDocumentItemWriter(properties, path, fileAction, firstDocument),
            properties.getWriteBlockSize());
  }

  private LocalDocumentItemWriter localDocumentItemWriter(DocumentExportProperties properties, File path, FileAction fileAction, Document firstDocument) {
    return new LocalDocumentItemWriter(outFile(properties.getQuery().getCollection(), path, firstDocument.get(properties.getFileNameSuffixColumn())),
            file -> fileAction.perform(file, true));
  }

  private File outFile(String collection, File directoryPath, String suffix) {
    // TODO: format date (suffix)
    File file = new File(directoryPath, String.format("%s_-_%s.json", collection, suffix));
    LOG.info("Exporting to temp file {}", file.getAbsolutePath());
    return file;
  }

  @Bean
  @StepScope
  public DocumentItemReader reader(ObjectSource<Document> documentSource,
                                   @Value("#{stepExecution.jobExecution.executionContext.get('exportProperties')}") DocumentExportProperties properties) {
    return new DocumentItemReader(documentSource, properties.getReadBlockSize());
  }

  @Bean
  @StepScope
  public ObjectSource logSource(@Value("#{jobParameters[start]}") String start,
                                @Value("#{jobParameters[end]}") String end,
                                @Value("#{stepExecution.jobExecution.executionContext.get('exportProperties')}") DocumentExportProperties properties) {

    return new SolrDocumentSource(
            properties.getZooKeeperConnectionString(),
            properties.getQuery(),
            start,
            end);
  }
}
