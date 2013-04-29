package com.continuuity.performance.gateway;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.gateway.collector.RestCollector;
import com.continuuity.gateway.util.Util;
import com.continuuity.passport.PassportConstants;
import com.continuuity.performance.benchmark.Agent;
import com.continuuity.performance.benchmark.AgentGroup;
import com.continuuity.performance.benchmark.BenchmarkException;
import com.continuuity.performance.benchmark.BenchmarkRunner;
import com.continuuity.performance.benchmark.SimpleAgentGroup;
import com.continuuity.performance.benchmark.SimpleBenchmark;
import com.google.common.collect.Lists;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class LoadGenerator extends SimpleBenchmark {

  String apikey = null;
  String hostname = null;
  String baseUrl = null;
  String destination = null;
  String requestUrl = null;
  String file = null;
  int length = 100;

  String [] wordList = shortList;

  Random seedRandom = new Random();

  @Override
  public Map<String, String> usage() {
    Map<String, String> usage = super.usage();
    usage.put("--base <url>", "The base URL of the gateway stream interface.");
    usage.put("--gateway <hostname>", "The hostname of the gateway. The rest " +
        "of the gateway URL will be constructed from the local default " +
        "configuration. Default is localhost.");
    usage.put("--stream <name>", "The name of the stream to send events to.");
    usage.put("--words <filename>", "To specify a file containing a " +
        "vocabulary for generating the random text, one word per line. ");
    usage.put("--length <num>", "To specify a length limit for the generated " +
        "random text. Default is 100 words.");
    return usage;
  }

  @Override
  public void configure(CConfiguration config) throws BenchmarkException {

    super.configure(config);

    apikey = config.get("apikey");
    baseUrl = config.get("base");
    hostname = config.get("gateway");
    destination = config.get("stream");
    file = config.get("words");
    length = config.getInt("length", length);
    boolean ssl = apikey != null;

    // determine the base url for the GET request
    if (baseUrl == null) baseUrl =
        Util.findBaseUrl(config, RestCollector.class, null, hostname, -1, ssl);
    if (baseUrl == null) {
      throw new BenchmarkException(
          "Can't figure out gateway URL. Please specify --base");
    } else {
      if (super.simpleConfig.verbose)
        System.out.println("Using base URL: " + baseUrl);
    }

    if (destination == null)
      throw new BenchmarkException(
          "Destination stream must be specified via --stream");
    else
      requestUrl = baseUrl + destination;

    if (file != null) {
      try {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        String line;
        List<String> words = Lists.newArrayList();
        while ((line = reader.readLine()) != null) {
          words.add(line);
        }
        reader.close();
        wordList = words.toArray(new String[words.size()]);
        System.out.println("Using word list in " + file + " (" + words.size()
            + " words).");
      } catch (IOException e) {
        throw new BenchmarkException("Cannot read word list from file " +
            file + ": " + e.getMessage(), e);
      }
    } else {
      System.out.println("Using built-in word list of 100 most frequent " +
          "english words.");
    }
  }

  @Override
  public AgentGroup[] getAgentGroups() {
    return new AgentGroup[] {
        new SimpleAgentGroup(super.simpleConfig) {
          @Override
          public String getName() {
            return "event generator";
          }

          @Override
          public Agent newAgent(int agentId, final int numAgents) {
            return new Agent(agentId) {
              @Override
              public long runOnce(long iteration)
                  throws BenchmarkException {

                // create a string of random words and length
                Random rand = new Random(seedRandom.nextLong());
                int numWords = rand.nextInt(length);
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < numWords; i++) {
                  int j = rand.nextInt(wordList.length);
                  String word = wordList[j];
                  if (rand.nextInt(7) == 0) {
                    word = word.toUpperCase();
                  }
                  builder.append(word);
                  builder.append(' ');
                }
                String body = builder.toString();

                if (isVerbose()) {
                  System.out.println(getName() + " " + this.getAgentId() +
                      " sending event number " + iteration + " with body: " +
                      body);
                }

                // create an HttpPost
                HttpPost post = new HttpPost(requestUrl);
                if (apikey != null) {
                  post.addHeader(PassportConstants.CONTINUUITY_API_KEY_HEADER, apikey);
                }
                byte[] binaryBody = body.getBytes();
                post.setEntity(new ByteArrayEntity(binaryBody));

                // post is now fully constructed, ready to send
                // prepare for HTTP
                try {
                  HttpClient client = new DefaultHttpClient();
                  HttpResponse response = client.execute(post);
                  if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
                    System.err.println(
                        "Unexpected HTTP response: " + response.getStatusLine());
                  client.getConnectionManager().shutdown();
                } catch (IOException e) {
                  System.err.println("Error sending HTTP request: " + e.getMessage());
                }
                return 1L;
              }
            };
          } // newAgent()
        } // new AgentGroup()
    }; // new AgentGroup[]
  } // getAgentGroups()


  public static void main(String[] args) throws Exception {
    args = Arrays.copyOf(args, args.length + 2);
    args[args.length - 2] = "--bench";
    args[args.length - 1] = LoadGenerator.class.getName();
    BenchmarkRunner.main(args);
  }

  static String[] shortList = {
      "be",
      "to",
      "of",
      "and",
      "a",
      "in",
      "that",
      "have",
      "I",
      "it",
      "for",
      "not",
      "on",
      "with",
      "he",
      "as",
      "you",
      "do",
      "at",
      "this",
      "but",
      "his",
      "by",
      "from",
      "they",
      "we",
      "say",
      "her",
      "she",
      "or",
      "an",
      "will",
      "my",
      "one",
      "all",
      "would",
      "there",
      "their",
      "what",
      "so",
      "up",
      "out",
      "if",
      "about",
      "who",
      "get",
      "which",
      "go",
      "me",
      "when",
      "make",
      "can",
      "like",
      "time",
      "no",
      "just",
      "him",
      "know",
      "take",
      "people",
      "into",
      "year",
      "your",
      "good",
      "some",
      "could",
      "them",
      "see",
      "other",
      "than",
      "then",
      "now",
      "look",
      "only",
      "come",
      "its",
      "over",
      "think",
      "also",
      "back",
      "after",
      "use",
      "two",
      "how",
      "our",
      "work",
      "first",
      "well",
      "way",
      "even",
      "new",
      "want",
      "because",
      "any",
      "these",
      "give",
      "day",
      "most"
  };
}
