/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */

package com.continuuity.internal.app.runtime.flow;

import com.continuuity.api.ApplicationSpecification;
import com.continuuity.api.annotation.Async;
import com.continuuity.api.annotation.Output;
import com.continuuity.api.annotation.ProcessInput;
import com.continuuity.api.annotation.UseDataSet;
import com.continuuity.api.data.DataSet;
import com.continuuity.api.data.DataSetContext;
import com.continuuity.api.flow.FlowSpecification;
import com.continuuity.api.flow.FlowletConnection;
import com.continuuity.api.flow.FlowletDefinition;
import com.continuuity.api.flow.flowlet.Callback;
import com.continuuity.api.flow.flowlet.FailurePolicy;
import com.continuuity.api.flow.flowlet.FailureReason;
import com.continuuity.api.flow.flowlet.Flowlet;
import com.continuuity.api.flow.flowlet.FlowletSpecification;
import com.continuuity.api.flow.flowlet.GeneratorFlowlet;
import com.continuuity.api.flow.flowlet.InputContext;
import com.continuuity.api.flow.flowlet.OutputEmitter;
import com.continuuity.api.metrics.Metrics;
import com.continuuity.app.Id;
import com.continuuity.app.program.Program;
import com.continuuity.app.program.Type;
import com.continuuity.app.queue.QueueName;
import com.continuuity.app.queue.QueueReader;
import com.continuuity.app.queue.QueueSpecification;
import com.continuuity.app.queue.QueueSpecificationGenerator.Node;
import com.continuuity.app.runtime.ProgramController;
import com.continuuity.app.runtime.ProgramOptions;
import com.continuuity.app.runtime.ProgramRunner;
import com.continuuity.app.runtime.RunId;
import com.continuuity.common.logging.common.LogWriter;
import com.continuuity.common.logging.logback.CAppender;
import com.continuuity.data.operation.ttqueue.QueueConsumer;
import com.continuuity.data.operation.ttqueue.QueueProducer;
import com.continuuity.internal.api.io.Schema;
import com.continuuity.internal.api.io.SchemaGenerator;
import com.continuuity.internal.api.io.UnsupportedTypeException;
import com.continuuity.internal.app.queue.QueueReaderFactory;
import com.continuuity.internal.app.queue.RoundRobinQueueReader;
import com.continuuity.internal.app.queue.SimpleQueueSpecificationGenerator;
import com.continuuity.internal.app.runtime.AbstractProgramController;
import com.continuuity.internal.app.runtime.DataSets;
import com.continuuity.internal.app.runtime.InstantiatorFactory;
import com.continuuity.internal.app.runtime.MultiOutputSubmitter;
import com.continuuity.internal.app.runtime.OutputSubmitter;
import com.continuuity.internal.app.runtime.TransactionAgentSupplier;
import com.continuuity.internal.app.runtime.TransactionAgentSupplierFactory;
import com.continuuity.internal.io.ReflectionDatumWriter;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;
import com.google.common.reflect.TypeToken;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public final class FlowletProgramRunner implements ProgramRunner {

  private static final Logger LOG = LoggerFactory.getLogger(FlowletProgramRunner.class);

  private final SchemaGenerator schemaGenerator;
  private final TransactionAgentSupplierFactory txAgentSupplierFactory;
  private final QueueReaderFactory queueReaderFactory;

  @Inject
  public FlowletProgramRunner(SchemaGenerator schemaGenerator,
                              TransactionAgentSupplierFactory txAgentSupplierFactory,
                              QueueReaderFactory queueReaderFactory, LogWriter logWriter) {
    this.schemaGenerator = schemaGenerator;
    this.txAgentSupplierFactory = txAgentSupplierFactory;
    this.queueReaderFactory = queueReaderFactory;
    CAppender.logWriter = logWriter;
  }

  @Override
  public ProgramController run(Program program, ProgramOptions options) {
    try {
      // Extract and verify parameters
      String flowletName = options.getName();

      int instanceId = Integer.parseInt(options.getArguments().getOption("instanceId", "-1"));
      Preconditions.checkArgument(instanceId >= 0, "Missing instance Id");

      int instanceCount = Integer.parseInt(options.getArguments().getOption("instances", "0"));
      Preconditions.checkArgument(instanceCount > 0, "Invalid or missing instance count");

      String runIdOption = options.getArguments().getOption("runId");
      Preconditions.checkNotNull(runIdOption, "Missing runId");
      RunId runId = RunId.from(runIdOption);

      ApplicationSpecification appSpec = program.getSpecification();
      Preconditions.checkNotNull(appSpec, "Missing application specification.");

      Type processorType = program.getProcessorType();
      Preconditions.checkNotNull(processorType, "Missing processor type.");
      Preconditions.checkArgument(processorType == Type.FLOW, "Only FLOW process type is supported.");

      String processorName = program.getProgramName();
      Preconditions.checkNotNull(processorName, "Missing processor name.");

      FlowSpecification flowSpec = appSpec.getFlows().get(processorName);
      FlowletDefinition flowletDef = flowSpec.getFlowlets().get(flowletName);

      Preconditions.checkNotNull(flowletDef, "Definition missing for flowlet \"%s\"", flowletName);
      ClassLoader classLoader = program.getMainClass().getClassLoader();
      Class<? extends Flowlet> flowletClass = (Class<? extends Flowlet>)Class.forName(
                                                      flowletDef.getFlowletSpec().getClassName(),
                                                      true, classLoader);

      Preconditions.checkArgument(Flowlet.class.isAssignableFrom(flowletClass), "%s is not a Flowlet.", flowletClass);

      // Creates opex related objects
      TransactionAgentSupplier txAgentSupplier = txAgentSupplierFactory.create(program);
      DataSetContext dataSetContext = txAgentSupplier.getDataSetContext();

      // Creates flowlet context
      final BasicFlowletContext flowletContext = new BasicFlowletContext(program, flowletName, instanceId, runId,
                                                                   DataSets.createDataSets(dataSetContext,
                                                                                           flowletDef.getDatasets()),
                                                                   flowletDef.getFlowletSpec(),
                                                                   flowletClass.isAnnotationPresent(Async.class));
      flowletContext.setInstanceCount(instanceCount);

      // Creates QueueSpecification
      Table<Node, String, Set<QueueSpecification>> queueSpecs =
        new SimpleQueueSpecificationGenerator(Id.Account.from(program.getAccountId()))
            .create(flowSpec);

      Flowlet flowlet = new InstantiatorFactory(false).get(TypeToken.of(flowletClass)).create();
      TypeToken<? extends Flowlet> flowletType = TypeToken.of(flowletClass);

      // Inject DataSet, OutputEmitter, Metric fields
      OutputSubmitter outputSubmitter = injectFields(flowlet, flowletType, flowletContext,
                                                     outputEmitterFactory(flowletName, flowletContext,
                                                                          flowletContext.getQueueProducer(),
                                                                          queueSpecs));

      Collection<ProcessSpecification> processSpecs =
        createProcessSpecification(flowletType,
                                   processMethodFactory(flowlet, flowletContext,
                                                        createSchemaCache(program),
                                                        txAgentSupplier,
                                                        outputSubmitter),
                                   processSpecificationFactory(program, queueReaderFactory,
                                                               flowletContext,
                                                               flowletName, queueSpecs),
                                   Lists.<ProcessSpecification>newLinkedList());

      FlowletProcessDriver driver = new FlowletProcessDriver(flowlet, flowletContext, processSpecs,
                                                             createCallback(flowlet, flowletDef.getFlowletSpec()));

      LOG.info("Starting flowlet: " + flowletContext);
      driver.start();
      LOG.info("Flowlet started: " + flowletContext);

      return programController(program.getProgramName(), flowletName, flowletContext, driver);

    } catch(Exception e) {
      throw Throwables.propagate(e);
    }
  }

  private ProgramController programController(String programName,
                                              final String flowletName,
                                              final BasicFlowletContext flowletContext,
                                              final FlowletProcessDriver driver) {
    return new AbstractProgramController(programName + ":" + flowletName, flowletContext.getRunId()) {
      @Override
      protected void doSuspend() throws Exception {
        LOG.info("Suspending flowlet: " + flowletContext);
        driver.suspend();
        LOG.info("Flowlet suspended: " + flowletContext);
      }

      @Override
      protected void doResume() throws Exception {
        LOG.info("Resuming flowlet: " + flowletContext);
        driver.resume();
        LOG.info("Flowlet resumed: " + flowletContext);
      }

      @Override
      protected void doStop() throws Exception {
        LOG.info("Stopping flowlet: " + flowletContext);
        driver.stopAndWait();
        LOG.info("Flowlet stopped: " + flowletContext);
      }

      @Override
      protected void doCommand(String name, Object value) throws Exception {
        Preconditions.checkState(getState() == State.SUSPENDED,
                                 "Cannot change instance count when flowlet is running.");
        if (!"instances".equals(name) || !(value instanceof Integer)) {
          return;
        }
        int instances = (Integer)value;
        LOG.info("Change flowlet instance count: " + flowletContext + ", new count is " + instances);
        flowletContext.setInstanceCount(instances);
        LOG.info("Flowlet instance count changed: " + flowletContext + ", new count is " + instances);
      }
    };
  }

  /**
   * Injects all {@link DataSet} and {@link OutputEmitter} fields.
   *
   * @return an {@link OutputSubmitter} that encapsulate all injected {@link OutputEmitter}
   *         that are {@link OutputSubmitter} as well.
   */
  private OutputSubmitter injectFields(Flowlet flowlet,
                                       TypeToken<? extends Flowlet> flowletType,
                                       BasicFlowletContext flowletContext,
                                       OutputEmitterFactory outputEmitterFactory) {

    ImmutableList.Builder<OutputSubmitter> outputSubmitters = ImmutableList.builder();

    // Walk up the hierarchy of flowlet class.
    for (TypeToken<?> type : flowletType.getTypes().classes()) {
      if (type.getRawType().equals(Object.class)) {
        break;
      }

      // Inject DataSet, OutputEmitter and Metrics fields.
      for (Field field : type.getRawType().getDeclaredFields()) {
        // Inject DataSet
        if (DataSet.class.isAssignableFrom(field.getType())) {
          UseDataSet dataset = field.getAnnotation(UseDataSet.class);
          if (dataset != null && !dataset.value().isEmpty()) {
            setField(flowlet, field, flowletContext.getDataSet(dataset.value()));
          }
          continue;
        }
        // Inject OutputEmitter
        if (OutputEmitter.class.equals(field.getType())) {
          TypeToken<?> outputType = TypeToken.of(((ParameterizedType)field.getGenericType())
                                                   .getActualTypeArguments()[0]);
          String outputName = field.isAnnotationPresent(Output.class) ?
            field.getAnnotation(Output.class).value() : FlowletDefinition.DEFAULT_OUTPUT;

          OutputEmitter<?> outputEmitter = outputEmitterFactory.create(outputName, outputType);
          setField(flowlet, field, outputEmitter);
          if (outputEmitter instanceof OutputSubmitter) {
            outputSubmitters.add((OutputSubmitter)outputEmitter);
          }
          continue;
        }
        if (Metrics.class.equals(field.getType())) {
          setField(flowlet, field, flowletContext.getMetrics());
        }
      }
    }

    return new MultiOutputSubmitter(outputSubmitters.build());
  }

  /**
   * Creates all {@link ProcessSpecification} for the process methods of the flowlet class.
   *
   * @param flowletType Type of the flowlet class represented by {@link TypeToken}.
   * @param processMethodFactory A {@link ProcessMethodFactory} for creating {@link ProcessMethod}.
   * @param processSpecFactory A {@link ProcessSpecificationFactory} for creating {@link ProcessSpecification}.
   * @param result A {@link Collection} for storing newly created {@link ProcessSpecification}.
   * @return The same {@link Collection} as the {@code result} parameter.
   */
  private Collection<ProcessSpecification> createProcessSpecification(TypeToken<? extends Flowlet> flowletType,
                                                                      ProcessMethodFactory processMethodFactory,
                                                                      ProcessSpecificationFactory processSpecFactory,
                                                                      Collection<ProcessSpecification> result)
                                                                                  throws NoSuchMethodException {

    if (GeneratorFlowlet.class.isAssignableFrom(flowletType.getRawType())) {
      Method method = flowletType.getRawType().getMethod("generate");
      ProcessMethod generatorMethod = processMethodFactory.create(method,
                                                                  TypeToken.of(void.class),
                                                                  Schema.of(Schema.Type.NULL));
      return ImmutableList.of(processSpecFactory.create(ImmutableSet.<String>of(),
                                                        Schema.of(Schema.Type.NULL),
                                                        generatorMethod));
    }

    // Walk up the hierarchy of flowlet class to get all process methods
    // It needs to be traverse twice because process method needs to know all output emitters.
    for (TypeToken<?> type : flowletType.getTypes().classes()) {
      if (type.getRawType().equals(Object.class)) {
        break;
      }
      // Extracts all process methods
      for (Method method : type.getRawType().getDeclaredMethods()) {
        ProcessInput processInputAnnotation = method.getAnnotation(ProcessInput.class);
        if (!method.getName().startsWith(FlowletDefinition.PROCESS_METHOD_PREFIX) && processInputAnnotation == null) {
          continue;
        }

        Set<String> inputNames;
        if (processInputAnnotation == null || processInputAnnotation.value().length == 0) {
          inputNames = ImmutableSet.of(FlowletDefinition.ANY_INPUT);
        } else {
          inputNames = ImmutableSet.copyOf(processInputAnnotation.value());
        }

        try {
          TypeToken<?> dataType = TypeToken.of(method.getGenericParameterTypes()[0]);
          Schema schema = schemaGenerator.generate(dataType.getType());

          ProcessMethod processMethod = processMethodFactory.create(method, dataType, schema);
          result.add(processSpecFactory.create(inputNames, schema, processMethod));
        } catch (UnsupportedTypeException e) {
          throw Throwables.propagate(e);
        }
      }
    }
    Preconditions.checkArgument(!result.isEmpty(), "No process method found for " + flowletType);
    return result;
  }

  private Callback createCallback(Flowlet flowlet, FlowletSpecification flowletSpec) {
    if (flowlet instanceof Callback) {
      return (Callback)flowlet;
    }
    final FailurePolicy failurePolicy = flowletSpec.getFailurePolicy();
    return new Callback() {
      @Override
      public void onSuccess(Object input, InputContext inputContext) {
        // No-op
      }

      @Override
      public FailurePolicy onFailure(Object input, InputContext inputContext, FailureReason reason) {
        return failurePolicy;
      }
    };
  }

  private OutputEmitterFactory outputEmitterFactory(final String flowletName,
                                                    final BasicFlowletContext flowletContext,
                                                    final QueueProducer queueProducer,
                                                    final Table<Node, String, Set<QueueSpecification>> queueSpecs) {
    return new OutputEmitterFactory() {
      @Override
      public OutputEmitter<?> create(String outputName, TypeToken<?> type) {
        try {
          Schema schema = schemaGenerator.generate(type.getType());

          Node flowlet = Node.flowlet(flowletName);
          for (QueueSpecification queueSpec : Iterables.concat(queueSpecs.row(flowlet).values())) {
            if (queueSpec.getQueueName().getSimpleName().equals(outputName)
                && queueSpec.getOutputSchema().equals(schema)) {
              return new DatumOutputEmitter<Object>(flowletContext, queueProducer, queueSpec.getQueueName(),
                                                    schema, new ReflectionDatumWriter(schema));
            }
          }

          throw new IllegalArgumentException(String.format("No queue specification found for %s, %s",
                                                           flowletName, type));

        } catch (UnsupportedTypeException e) {
          throw Throwables.propagate(e);
        }
      }
    };
  }

  private ProcessMethodFactory processMethodFactory(final Flowlet flowlet,
                                                    final BasicFlowletContext flowletContext,
                                                    final SchemaCache schemaCache,
                                                    final TransactionAgentSupplier txAgentSupplier,
                                                    final OutputSubmitter outputSubmitter) {
    return new ProcessMethodFactory() {
      @Override
      public ProcessMethod create(Method method, TypeToken<?> dataType, Schema schema) {
        return ReflectionProcessMethod.create(flowlet, flowletContext, method, dataType, schema,
                                              schemaCache, txAgentSupplier, outputSubmitter);


      }
    };
  }

  private ProcessSpecificationFactory processSpecificationFactory(final Program program,
                                                                  final QueueReaderFactory queueReaderFactory,
                                                                  final BasicFlowletContext flowletContext,
                                                                  final String flowletName,
                                                                  final Table<Node,
                                                                              String,
                                                                              Set<QueueSpecification>> queueSpecs) {
    return new ProcessSpecificationFactory() {

      private final Supplier<QueueConsumer> queueConsumer = new Supplier<QueueConsumer>() {
        @Override
        public QueueConsumer get() {
          return flowletContext.getQueueConsumer();
        }
      };

      @Override
      public ProcessSpecification create(Set<String> inputNames, Schema schema, ProcessMethod method) {
        List<QueueReader> queueReaders = Lists.newLinkedList();

        for (Map.Entry<Node, Set<QueueSpecification>> entry : queueSpecs.column(flowletName).entrySet()) {
          for (QueueSpecification queueSpec : entry.getValue()) {
            QueueName queueName = queueSpec.getQueueName();

            if (queueSpec.getInputSchema().equals(schema)
              && (inputNames.contains(queueName.getSimpleName())
              || inputNames.contains(FlowletDefinition.ANY_INPUT))) {

              int numGroups = (entry.getKey().getType() == FlowletConnection.Type.STREAM)
                                  ? -1
                                  : getNumGroups(Iterables.concat(queueSpecs.row(entry.getKey()).values()), queueName);

              queueReaders.add(queueReaderFactory.create(program, queueName, queueConsumer, numGroups));
            }
          }
        }

        return new ProcessSpecification(new RoundRobinQueueReader(queueReaders), method);
      }
    };
  }

  private int getNumGroups(Iterable<QueueSpecification> queueSpecs, QueueName queueName) {
    int numGroups = 0;
    for (QueueSpecification queueSpec : queueSpecs) {
      if (queueName.equals(queueSpec.getQueueName())) {
        numGroups++;
      }
    }
    return numGroups;
  }

  private void setField(Flowlet flowlet, Field field, Object value) {
    if (!field.isAccessible()) {
      field.setAccessible(true);
    }
    try {
      field.set(flowlet, value);
    } catch (IllegalAccessException e) {
      throw Throwables.propagate(e);
    }
  }

  private SchemaCache createSchemaCache(Program program) throws ClassNotFoundException {
    ImmutableSet.Builder<Schema> schemas = ImmutableSet.builder();

    for (FlowSpecification flowSpec : program.getSpecification().getFlows().values()) {
      for (FlowletDefinition flowletDef : flowSpec.getFlowlets().values()) {
        schemas.addAll(Iterables.concat(flowletDef.getInputs().values()));
        schemas.addAll(Iterables.concat(flowletDef.getOutputs().values()));
      }
    }

    return new SchemaCache(schemas.build(), program.getMainClass().getClassLoader());
  }

  private static interface OutputEmitterFactory {
    OutputEmitter<?> create(String outputName, TypeToken<?> type);
  }

  private static interface ProcessMethodFactory {
    ProcessMethod create(Method method, TypeToken<?> dataType, Schema schema);
  }

  private static interface ProcessSpecificationFactory {
    ProcessSpecification create(Set<String> inputNames, Schema schema, ProcessMethod method);
  }
}
