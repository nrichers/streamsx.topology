package com.ibm.streamsx.topology.test.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;
import com.ibm.streamsx.topology.TStream;
import com.ibm.streamsx.topology.Topology;
import com.ibm.streamsx.topology.context.StreamsContext;
import com.ibm.streamsx.topology.context.StreamsContextFactory;
import com.ibm.streamsx.topology.function.Function;
import com.ibm.streamsx.topology.function.ToIntFunction;
import com.ibm.streamsx.topology.function.UnaryOperator;
import com.ibm.streamsx.topology.generator.spl.SPLGenerator;
import com.ibm.streamsx.topology.test.AllowAll;
import com.ibm.streamsx.topology.test.TestTopology;
import com.ibm.streamsx.topology.tester.Condition;
import com.ibm.streamsx.topology.tester.Tester;
import com.ibm.streams.operator.PERuntime;

public class LowLatencyTest extends TestTopology {
    @Test
    public void simpleLowLatencyTest() throws Exception{
        assumeTrue(SC_OK);
        Topology topology = new Topology("lowLatencyTest");

        // Construct topology
        TStream<String> ss = topology.strings("hello");
        TStream<String> ss1 = ss.transform(getPEId(), String.class).lowLatency();
        TStream<String> ss2 = ss1.transform(getPEId(), String.class).endLowLatency();
        ss2.print();
        
        StreamsContextFactory.getStreamsContext(StreamsContext.Type.TOOLKIT).submit(topology).get();
    }
    
    @Test
    public void multipleRegionLowLatencyTest() throws Exception{
        assumeTrue(SC_OK);
        Topology topology = new Topology("lowLatencyTest");

        // Construct topology
        TStream<String> ss = topology.strings("hello")
                .transform(getPEId(), String.class).transform(getPEId(), String.class);
        
        TStream<String> ss1 = ss.transform(getPEId(), String.class).lowLatency();
        TStream<String> ss2 = ss1.transform(getPEId(), String.class).
                transform(getPEId(), String.class).endLowLatency().transform(getPEId(), String.class);
        TStream<String> ss3 = ss2.transform(getPEId(), String.class).lowLatency();
        ss3.transform(getPEId(), String.class).transform(getPEId(), String.class)
            .endLowLatency().print();
        
        StreamsContextFactory.getStreamsContext(StreamsContext.Type.TOOLKIT).submit(topology).get();
    }
    
    @Test
    public void threadedPortTest() throws Exception{
        assumeTrue(SC_OK);
        Topology topology = new Topology("lowLatencyTest");

        // Construct topology
        TStream<String> ss = topology.strings("hello").lowLatency();
        TStream<String> ss1 = ss.transform(getPEId(), String.class);
        TStream<String> ss2 = ss1.transform(getPEId(), String.class).endLowLatency();
        
        SPLGenerator generator = new SPLGenerator();
        JSONObject graph = topology.builder().complete();
        generator.generateSPL(graph);
        
        JSONArray ops = (JSONArray)graph.get("operators");
        for(Object opObj : ops){
            JSONObject op = (JSONObject)opObj;
            String lowLatencyTag = (String) op.get("lowLatencyTag");
            String kind = (String)op.get("kind");
            JSONObject queue = (JSONObject) op.get("queue");
            if(queue != null && (lowLatencyTag!=null || lowLatencyTag.equals(""))){
                throw new IllegalStateException("Operator has threaded port when it shouldn't.");
            }
            if(queue != null 
                    && kind.equals("com.ibm.streamsx.topology.functional.java::FunctionTransform")){
                throw new IllegalStateException("Transform operator expecting threaded port; none found.");
            }
        }
    }
    

    
    @SuppressWarnings("serial")
    private static Function<String, String> getPEId(){
        return new Function<String, String>(){
            @Override
            public String apply(String v) {
                return PERuntime.getCurrentContext().getPE().getPEId().toString();
            }

        };
    }
    
    @Test
    public void testLowLatencySplit() throws Exception {
        assumeTrue(SC_OK);
        
        // lowLatency().split() is an interesting case because split()
        // has >1 oports.
        
        final Topology topology = new Topology("testLowLatencySplit");
        
        int splitWidth = 3;
        String[] strs = {"ch0", "ch1", "ch2"};
        TStream<String> s1 = topology.strings(strs);

        s1 = s1.isolate();
        s1 = s1.lowLatency();
        /////////////////////////////////////
        
        // assume that if s1.modify and the split().[modify()] are
        // in the same PE, that s1.split() is in the same too
        TStream<String> s2 = s1.modify(unaryGetPEId());
        
        List<TStream<String>> splits = s1
                .split(splitWidth, roundRobinSplitter());

        List<TStream<String>> splitChResults = new ArrayList<>();
        for(int i = 0; i < splits.size(); i++) {
            splitChResults.add( splits.get(i).modify(unaryGetPEId()) );
        }
        
        TStream<String> splitChFanin = splitChResults.get(0).union(
                        new HashSet<>(splitChResults.subList(1, splitChResults.size())));
        
        /////////////////////////////////////
        TStream<String> all = splitChFanin.endLowLatency();
        all.print();

        Tester tester = topology.getTester();
        
        TStream<String> dupAll = all.filter(new AllowAll<String>());
        Condition<Long> uCount = tester.tupleCount(dupAll, strs.length);
        
        Condition<List<String>> contents = tester.stringContents(dupAll, "");
        Condition<List<String>> s2contents = tester.stringContents(s2, "");

        complete(tester, uCount, 10, TimeUnit.SECONDS);

        Set<String> peIds = new HashSet<>();
        for (String s : contents.getResult()) {
            peIds.add(s);
        }
        for (String s : s2contents.getResult()) {
            peIds.add(s);
        }
        assertEquals("peIds: "+peIds, 1, peIds.size() );
    }
    
    @SuppressWarnings("serial")
    static UnaryOperator<String> unaryGetPEId() {
        return new UnaryOperator<String>() {

            @Override
            public String apply(String v) {
                return PERuntime.getCurrentContext().getPE().getPEId().toString();
            }
        }; 
    }
    
    @SuppressWarnings("serial")
    private static ToIntFunction<String> roundRobinSplitter() {
        return new ToIntFunction<String>() {
            private int i;

            @Override
            public int applyAsInt(String s) {
                return i++;
            }
        };
    }

}