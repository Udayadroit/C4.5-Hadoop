/**
 * This file is part of an implementation of C4.5 by Yohann Jardin.
 * 
 * This implementation of C4.5 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This implementation of C4.5 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this implementation of C4.5. If not, see <http://www.gnu.org/licenses/>.
 */

package full_MapReduce;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;

public class C4_5 {

    private static Path input_path;
    private static Path tmp_path;
    private static Path summarized_data_path;
    private static Path calc_attributes_info_path;
    private static Path best_attribute_result_path;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: main/C4_5 <input path> <tmp path>");
            System.exit(-1);
        }

        input_path = new Path(args[0]);
        tmp_path = new Path(args[1]);
        summarized_data_path = new Path(args[1] + "/summarized_data");
        calc_attributes_info_path = new Path(args[1] + "/calc_attributes_info");
        best_attribute_result_path = new Path(args[1] + "/best_attribute_result");
        FileSystem fs = FileSystem.get(new Configuration());

        //Job which key result is a line of data and value is a counter
        summarizeData();

        Map<Map<String, String>, String> classification = new HashMap<Map<String, String>, String>();
        Deque<Map<String, String>> conditions_to_test = new ArrayDeque<Map<String, String>>();

        Map<String, String> init = new HashMap<String, String>();
        conditions_to_test.add(init);
        
        String exceptions_conditions = "";

        while (!conditions_to_test.isEmpty()) {

            Map<String, String> conditions = conditions_to_test.pop();
            calcAttributesInfo(conditions);
            findBestAttribute();
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(new Path(best_attribute_result_path + "/part-r-00000"))));
                String[] line = br.readLine().split(",");

                String attribute = line[0];
                boolean cannot_go_deeper = line[line.length - 1].equals("0");

                Map<String, String> next_conditions;
                for (int i = 1; i < line.length - 1; ++i) {
                    String[] value_info = line[i].split(" ");

                    next_conditions = new HashMap<String, String>(conditions);
                    next_conditions.put(attribute, value_info[0]);

                    if (cannot_go_deeper || value_info[1].equals("1")) {
                        classification.put(next_conditions, value_info[2]);
                    } else {
                        conditions_to_test.add(next_conditions);
                    }

                }
            } catch (Exception e) {
                    List<String> key_sorted = new ArrayList<String>(conditions.keySet());
                    Collections.sort(key_sorted);

                    for (int i = 0; i < key_sorted.size(); ++i) {
                        exceptions_conditions += key_sorted.get(i) + "=" + conditions.get(key_sorted.get(i)) + ", ";
                    }

                    exceptions_conditions += "\n";
            }
            fs.delete(calc_attributes_info_path, true);
            fs.delete(best_attribute_result_path, true);

        }

        printClassifications(classification);
        System.out.println(exceptions_conditions);

        fs.delete(tmp_path, true);
    }

    private static void summarizeData() throws Exception {
        Job job = Job.getInstance();
        job.setJarByClass(C4_5.class);
        job.setJobName("C4.5_summarizeData");

        FileInputFormat.addInputPath(job, input_path);
        FileOutputFormat.setOutputPath(job, summarized_data_path);

        job.setMapperClass(SummarizeMapper.class);
        job.setReducerClass(SummarizeReducer.class);

        job.setOutputKeyClass(TextArrayWritable.class);
        job.setOutputValueClass(IntWritable.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        job.waitForCompletion(false);
    }

    private static void calcAttributesInfo(Map<String, String> conditions) throws Exception {
        Configuration conf = new Configuration();
        for (Entry<String, String> condition : conditions.entrySet()) {
            conf.setStrings(condition.getKey(), condition.getValue());
        }

        Job job = Job.getInstance(conf);
        job.setJarByClass(C4_5.class);
        job.setJobName("C4.5_calcAttributesInfo");

        FileInputFormat.addInputPath(job, summarized_data_path);
        FileOutputFormat.setOutputPath(job, calc_attributes_info_path);

        job.setMapperClass(AttributeInfoMapper.class);
        job.setReducerClass(AttributeInfoReducer.class);

        job.setInputFormatClass(SequenceFileInputFormat.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(AttributeCounterWritable.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(MapWritable.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        job.waitForCompletion(false);
    }

    private static void findBestAttribute() throws Exception {
        Job job = Job.getInstance();
        job.setJarByClass(C4_5.class);
        job.setJobName("C4.5_findBestAttribute");

        FileInputFormat.addInputPath(job, calc_attributes_info_path);
        FileOutputFormat.setOutputPath(job, best_attribute_result_path);

        job.setMapperClass(FindBestAttributeMapper.class);
        job.setReducerClass(FindBestAttributeReducer.class);

        job.setInputFormatClass(SequenceFileInputFormat.class);
        job.setMapOutputKeyClass(NullWritable.class);
        job.setMapOutputValueClass(AttributeGainRatioWritable.class);
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        job.waitForCompletion(false);
    }

    private static void printClassifications(Map<Map<String, String>, String> classification) {
        List<String> msgs = new ArrayList<String>();

        String msg;
        for (Map<String, String> conditions : classification.keySet()) {
            msg = "";

            List<String> key_sorted = new ArrayList<String>(conditions.keySet());
            Collections.sort(key_sorted);

            for (int i = 0; i < key_sorted.size(); ++i) {
                msg += key_sorted.get(i) + "=" + conditions.get(key_sorted.get(i)) + ", ";
            }

            msg += "CLASSIFICATION: " + classification.get(conditions);

            msgs.add(msg);
        }

        Collections.sort(msgs);

        for (int i = 0; i < msgs.size(); ++i) {
            System.out.println(msgs.get(i));
        }

    }
}
