name := "jio"

version := "0.1"

scalaVersion := "2.13.0"

libraryDependencies ++= Seq(
	"junit" % "junit" % "4.12" % Test
)

javacOptions ++= Seq(
	"-source", "1.8",
	"-target", "1.8"
)

scalacOptions ++= Seq(
	"-target:jvm-1.8"
)