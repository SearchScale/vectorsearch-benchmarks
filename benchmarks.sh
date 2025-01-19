mvn clean package

jq -c '.[]' jobs.json | while read i; do

    benchmarkID=$(echo $i | jq .benchmarkID | tr -d '"');
    datasetFile=$(echo $i | jq .datasetFile | tr -d '"');
    indexOfVector=$(echo $i | jq .indexOfVector | tr -d '"');
    vectorColName=$(echo $i | jq .vectorColName | tr -d '"');
    numDocs=$(echo $i | jq .numDocs | tr -d '"');
    vectorDimension=$(echo $i | jq .vectorDimension | tr -d '"');
    queryFile=$(echo $i | jq .queryFile | tr -d '"');
    commitFreq=$(echo $i | jq .commitFreq | tr -d '"');
    topK=$(echo $i | jq .topK | tr -d '"');
    hnswThreads=$(echo $i | jq .hnswThreads | tr -d '"');
    cuvsWriterThreads=$(echo $i | jq .cuvsWriterThreads | tr -d '"');
    mergeStrategy=$(echo $i | jq .mergeStrategy | tr -d '"');
    queryThreads=$(echo $i | jq .queryThreads | tr -d '"');
    createIndexInMemory=$(echo $i | jq .createIndexInMemory | tr -d '"');
    cleanIndexDirectory=$(echo $i | jq .cleanIndexDirectory | tr -d '"');
    saveResultsOnDisk=$(echo $i | jq .saveResultsOnDisk | tr -d '"');
    hasColNames=$(echo $i | jq .hasColNames | tr -d '"');
    algoToRun=$(echo $i | jq .algoToRun | tr -d '"');
    hnswMaxConn=$(echo $i | jq .hnswMaxConn | tr -d '"');
    hnswBeamWidth=$(echo $i | jq .hnswBeamWidth | tr -d '"');
    hnswVisitedLimit=$(echo $i | jq .hnswVisitedLimit | tr -d '"');
    cagraIntermediateGraphDegree=$(echo $i | jq .cagraIntermediateGraphDegree | tr -d '"');
    cagraGraphDegree=$(echo $i | jq .cagraGraphDegree | tr -d '"');
    cagraITopK=$(echo $i | jq .cagraITopK | tr -d '"');
    cagraSearchWidth=$(echo $i | jq .cagraSearchWidth | tr -d '"');

    java -jar target/vectorsearch-benchmarks-1.0-jar-with-dependencies.jar ${benchmarkID} ${datasetFile} ${indexOfVector} ${vectorColName} ${numDocs} ${vectorDimension} ${queryFile} ${commitFreq} ${topK} ${hnswThreads} ${cuvsWriterThreads} ${mergeStrategy} ${queryThreads} ${createIndexInMemory} ${cleanIndexDirectory} ${saveResultsOnDisk} ${hasColNames} ${algoToRun} ${hnswMaxConn} ${hnswBeamWidth} ${hnswVisitedLimit} ${cagraIntermediateGraphDegree} ${cagraGraphDegree} ${cagraITopK} ${cagraSearchWidth}

done