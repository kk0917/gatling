/*
 * Copyright 2011-2019 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.recorder.scenario.template

import io.gatling.http.client.ahc.uri.Uri
import io.gatling.recorder.scenario.{ ProtocolDefinition, ScenarioElement, TagElement }
import io.gatling.recorder.scenario.{ PauseElement, RequestElement }
import io.gatling.recorder.config.RecorderConfiguration

import com.dongxiguo.fastring.Fastring.Implicits._

private[scenario] object SimulationTemplate {

  def render(
    packageName:         String,
    simulationClassName: String,
    protocol:            ProtocolDefinition,
    headers:             Map[Int, Seq[(String, String)]],
    scenarioName:        String,
    scenarioElements:    Either[Seq[ScenarioElement], Seq[Seq[ScenarioElement]]]
  )(implicit config: RecorderConfiguration): String = {

    def renderPackage = if (!packageName.isEmpty) fast"package $packageName\n" else ""

    def renderHeaders = {

      def printHeaders(headers: Seq[(String, String)]) = headers match {
        case Seq((name, value)) =>
          fast"Map(${protectWithTripleQuotes(name)} -> ${protectWithTripleQuotes(value)})"
        case _ =>
          val mapContent = headers.map { case (name, value) => fast"		${protectWithTripleQuotes(name)} -> ${protectWithTripleQuotes(value)}" }.mkFastring(",\n")
          fast"""Map(
$mapContent)"""
      }

      headers
        .map { case (headersBlockIndex, headersBlock) => fast"""	val ${RequestTemplate.headersBlockName(headersBlockIndex)} = ${printHeaders(headersBlock)}""" }
        .mkFastring("\n\n")
    }

    def renderScenarioElement(se: ScenarioElement, extractedUris: ExtractedUris) = se match {
      case TagElement(text)        => fast"// $text"
      case PauseElement(duration)  => PauseTemplate.render(duration)
      case request: RequestElement => RequestTemplate.render(simulationClassName, request, extractedUris)
    }

    def renderProtocol(p: ProtocolDefinition) = ProtocolTemplate.render(p)

    def renderScenario(extractedUris: ExtractedUris) = {
      scenarioElements match {
        case Left(elements) =>
          val scenarioElements = elements.map { element =>
            val prefix = element match {
              case TagElement(_) => ""
              case _             => "."
            }
            fast"$prefix${renderScenarioElement(element, extractedUris)}"
          }.mkFastring("\n\t\t")

          fast"""val scn = scenario("$scenarioName")
		$scenarioElements"""

        case Right(chains) =>
          val chainElements = chains.zipWithIndex.map {
            case (chain, i) =>
              var firstNonTagElement = true
              val chainContent = chain.map { element =>
                val prefix = element match {
                  case TagElement(_) => ""
                  case _ => if (firstNonTagElement) {
                    firstNonTagElement = false; ""
                  } else "."
                }
                fast"$prefix${renderScenarioElement(element, extractedUris)}"
              }.mkFastring("\n\t\t")
              fast"val chain_$i = $chainContent"
          }.mkFastring("\n\n")

          val chainsList = chains.indices.map(i => fast"chain_$i").mkFastring(", ")

          fast"""$chainElements

	val scn = scenario("$scenarioName").exec(
		$chainsList)"""
      }

    }

    def flatScenarioElements(scenarioElements: Either[Seq[ScenarioElement], Seq[Seq[ScenarioElement]]]): Seq[ScenarioElement] =
      scenarioElements match {
        case Left(elements)  => elements
        case Right(elements) => elements.flatten
      }

    val extractedUris = new ExtractedUris(flatScenarioElements(scenarioElements))
    val nonBaseUrls = extractedUris.vals.filter(value => Uri.create(value.value).getBaseUrl != protocol.baseUrl)

    fast"""$renderPackage
import scala.concurrent.duration._

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.jdbc.Predef._

class $simulationClassName extends Simulation {

	val httpProtocol = http${renderProtocol(protocol)}

$renderHeaders

${ValuesTemplate.render(nonBaseUrls)}

	${renderScenario(extractedUris)}

	setUp(scn.inject(atOnceUsers(1))).protocols(httpProtocol)
}""".toString()
  }
}
