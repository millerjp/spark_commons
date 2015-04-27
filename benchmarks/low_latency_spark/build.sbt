import AssemblyKeys._

name := "interactive_spark_benchmarks"

version := "0.2.0"

scalaVersion := "2.10.4"

val Spark = "1.1.0"
val SparkCassandra = "1.1.1"

resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  "org.apache.spark" % "spark-core_2.10" % Spark % "provided",
  "org.apache.spark" % "spark-streaming_2.10" % Spark % "provided",
  "org.apache.spark" % "spark-streaming-zeromq_2.10" % Spark % "provided",
  "com.rabbitmq" % "amqp-client" % "3.4.4",
  ("com.datastax.spark" %% "spark-cassandra-connector" % SparkCassandra withSources() withJavadoc()).
    exclude("com.esotericsoftware.minlog", "minlog").
    exclude("commons-beanutils","commons-beanutils"),
  ("com.datastax.spark" %% "spark-cassandra-connector-java" % SparkCassandra withSources() withJavadoc()).
    exclude("org.apache.spark","spark-core"),
   "org.apache.commons" % "commons-math3" % "3.4.1",
  "net.jpountz.lz4" % "lz4" % "1.2.0",
  "com.typesafe.play" %% "play-json" % "2.2.1",
  "org.scalatest" % "scalatest_2.10" % "2.2.1" % "test",
  "org.mockito" % "mockito-all" % "1.10.19" % "test",
  "com.github.javafaker" % "javafaker" % "0.5"
)

//We do this so that Spark Dependencies will not be bundled with our fat jar but will still be included on the classpath
//When we do a sbt/run
run in Compile <<= Defaults.runTask(fullClasspath in Compile, mainClass in (Compile, run), runner in (Compile, run))

assemblySettings

mainClass in assembly := Some("pro.foundev.BenchmarkLauncher")

test in assembly := {}

traceLevel in run := 0

fork in run := true

mergeStrategy in assembly := {
  case PathList("META-INF", "ECLIPSEF.RSA", xs @ _*)         => MergeStrategy.discard
  case PathList("META-INF", "mailcap", xs @ _*)         => MergeStrategy.discard
  case PathList("org", "apache","commons","collections", xs @ _*) => MergeStrategy.first
  case PathList("org", "apache","commons","logging", xs @ _*) => MergeStrategy.first
  case PathList(ps @ _*) if ps.last == "Driver.properties" => MergeStrategy.first
  case PathList(ps @ _*) if ps.last == "plugin.properties" => MergeStrategy.discard
  case x =>
    val oldStrategy = (mergeStrategy in assembly).value
    oldStrategy(x)
}
