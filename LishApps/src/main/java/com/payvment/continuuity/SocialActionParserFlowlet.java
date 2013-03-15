package com.payvment.continuuity;

import com.continuuity.api.annotation.ProcessInput;
import com.continuuity.api.common.Bytes;
import com.continuuity.api.flow.flowlet.*;
//import com.continuuity.api.flow.flowlet.ComputeFlowlet;
//import com.continuuity.api.flow.flowlet.FailureHandlingPolicy;
//import com.continuuity.api.flow.flowlet.OutputCollector;
//import com.continuuity.api.flow.flowlet.StreamsConfigurator;
//import com.continuuity.api.flow.flowlet.Tuple;
//import com.continuuity.api.flow.flowlet.TupleContext;
//import com.continuuity.api.flow.flowlet.TupleSchema;
//import com.continuuity.api.flow.flowlet.builders.TupleBuilder;
import com.google.common.base.Throwables;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.payvment.continuuity.entity.SocialAction;

/**
 * Flowlet parses social actions from their JSON format into an internal
 * {@link SocialAction} object and then into a tuple to the next flowlet.
 */
public class SocialActionParserFlowlet extends AbstractFlowlet {

  static int numProcessed = 0;
  public static String NAME = "action_parser";


    @Override
    public FlowletSpecification configure() {
        return FlowletSpecification.Builder.with()
                .setName(NAME)
                .setDescription("")
                .build();
    }

//  @Override
//  public void configure(StreamsConfigurator configurator) {
//    // Apply default stream schema to default tuple input stream
//    configurator
//        .getDefaultTupleInputStream()
//        .setSchema(TupleSchema.EVENT_SCHEMA);
//
//    // Apply internal follow-event tuple schema to output stream
//    configurator
//        .getDefaultTupleOutputStream()
//        .setSchema(SocialActionFlow.SOCIAL_ACTION_TUPLE_SCHEMA);
//  }


    OutputEmitter<SocialAction> socialActionOutputEmitter;

    /**
     * JSON parser
     */
    private final Gson gson = new Gson();


    @ProcessInput(SocialActionFlow.inputStream)
    public void process(StreamEvent event) {
        String jsonEventString = new String(Bytes.toBytes(event.getBody()));

        // Perform any necessary pre-processing
        jsonEventString = preProcessSocialActionJSON(jsonEventString);

        // Parse social action JSON using GSON
        SocialAction action = null;
        try {
            action =
                    this.gson.fromJson(jsonEventString, SocialAction.class);
        } catch (JsonParseException jpe) {
            Throwables.propagate(jpe);
        } finally {

            // Emit tuple
            socialActionOutputEmitter.emit(action);
            numProcessed++;
        }
    }


// Old logic
//  @Override
//  public void process(Tuple tuple, TupleContext context,
//      OutputCollector collector) {
//
//    // Grab JSON string from event-stream tuple
//    String jsonEventString = new String((byte[])tuple.get("body"));
//
//    // Perform any necessar pre-processing
//    jsonEventString = preProcessSocialActionJSON(jsonEventString);
//
//    // Parse social action JSON using GSON
//    SocialAction action = null;
//    try {
//      action =
//          this.gson.fromJson(jsonEventString, SocialAction.class);
//    } catch (JsonParseException jpe) {
//      getFlowletContext().getLogger().error(
//          "Error parsing JSON input string (" + jsonEventString + ")");
//      throw jpe;
//    }
//
//    // Define and emit output tuple
//    Tuple outputTuple = new TupleBuilder().set("action", action).create();
//    collector.add(outputTuple);
//  }

//  @Override
//  public void onSuccess(Tuple tuple, TupleContext context) {
//    numProcessed++;
//  }

//  @Override
//  public FailureHandlingPolicy onFailure(Tuple tuple, TupleContext context,
//      FailureReason reason) {
//    getFlowletContext().getLogger().error(
//        "SocialActionParser Flowet Processing Failed : " + reason.toString() +
//        ", retrying");
//    return FailureHandlingPolicy.RETRY;
//  }

  static String preProcessSocialActionJSON(String jsonEventString) {
    return jsonEventString.replaceFirst("@id", "id");
  }
}
