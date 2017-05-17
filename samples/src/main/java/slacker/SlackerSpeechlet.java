/**
    Copyright 2014-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.

    Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with the License. A copy of the License is located at

        http://aws.amazon.com/apache2.0/

    or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package slacker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazon.speech.slu.Intent;
import com.amazon.speech.slu.Slot;
import com.amazon.speech.speechlet.IntentRequest;
import com.amazon.speech.speechlet.LaunchRequest;
import com.amazon.speech.speechlet.Session;
import com.amazon.speech.speechlet.SessionEndedRequest;
import com.amazon.speech.speechlet.SessionStartedRequest;
import com.amazon.speech.speechlet.Speechlet;
import com.amazon.speech.speechlet.SpeechletException;
import com.amazon.speech.speechlet.SpeechletResponse;
import com.amazon.speech.ui.OutputSpeech;
import com.amazon.speech.ui.PlainTextOutputSpeech;
import com.amazon.speech.ui.SsmlOutputSpeech;
import com.amazon.speech.ui.Reprompt;
import com.amazon.speech.ui.SimpleCard;
import com.amazonaws.util.json.JSONArray;
import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;
import com.amazonaws.util.json.JSONTokener;

/**
 * This sample shows how to create a Lambda function for handling Alexa Skill
 * requests that:
 * <ul>
 * <li><b>Web service</b>: communicate with an external web service to get tide
 * data from NOAA CO-OPS API (http://tidesandcurrents.noaa.gov/api/)</li>
 * <li><b>Multiple optional slots</b>: has 2 slots (city and date), where the
 * user can provide 0, 1, or 2 values, and assumes defaults for the unprovided
 * values</li>
 * <li><b>DATE slot</b>: demonstrates date handling and formatted date responses
 * appropriate for speech</li>
 * <li><b>Custom slot type</b>: demonstrates using custom slot types to handle a
 * finite set of known values</li>
 * <li><b>SSML</b>: Using SSML tags to control how Alexa renders the
 * text-to-speech</li>
 * <li><b>Pre-recorded audio</b>: Uses the SSML 'audio' tag to include an ocean
 * wave sound in the welcome response.</li>
 * <p>
 * - Dialog and Session state: Handles two models, both a one-shot ask and tell
 * model, and a multi-turn dialog model. If the user provides an incorrect slot
 * in a one-shot model, it will direct to the dialog model. See the examples
 * section for sample interactions of these models.
 * </ul>
 * <p>
 * <h2>Examples</h2>
 * <p>
 * <b>One-shot model</b>
 * <p>
 * User: "Alexa, ask Tide Pooler when is the high tide in Seattle on Saturday"
 * Alexa: "Saturday June 20th in Seattle the first high tide will be around 7:18
 * am, and will peak at ...""
 * <p>
 * <b>Dialog model</b>
 * <p>
 * User: "Alexa, open Tide Pooler"
 * <p>
 * Alexa: "Welcome to Tide Pooler. Which city would you like tide information
 * for?"
 * <p>
 * User: "Seattle"
 * <p>
 * Alexa: "For which date?"
 * <p>
 * User: "this Saturday"
 * <p>
 * Alexa: "Saturday June 20th in Seattle the first high tide will be around 7:18
 * am, and will peak at ..."
 */
public class SlackerSpeechlet implements Speechlet {
	private static final Logger log = LoggerFactory.getLogger(SlackerSpeechlet.class);
	private static final String SLOT_EMPLOYEES = "Employees";
	private static final String SLOT_VISITORS = "Visitor";

	private static final String DATUM = "MLLW";
	private static final String ENDPOINT = "http://tidesandcurrents.noaa.gov/api/datagetter";
	private static final HashMap<String, String> NAMES = new HashMap<String, String>();
	static {
		NAMES.put("Geoff", "@geoff");
		NAMES.put("Jay", "@jay");
		NAMES.put("Josh", "@josh");
		NAMES.put("Kathy", "@kathyhoang");
		NAMES.put("Keenan", "@keenan");
		NAMES.put("Kevin", "@kevin");
		NAMES.put("Colin", "@khaullen");
		NAMES.put("Kyleigh", "@kyleigh");
		NAMES.put("Eric", "@liueric");
		NAMES.put("Nate", "@nate");
		NAMES.put("Nina", "@nina");
		NAMES.put("Paul V", "@paul");
		NAMES.put("Paul L", "@paull");
		NAMES.put("Will", "@will");
	}

	@Override
	public void onSessionStarted(final SessionStartedRequest request, final Session session) throws SpeechletException {
		log.info("onSessionStarted requestId={}, sessionId={}", request.getRequestId(), session.getSessionId());

		// any initialization logic goes here
	}

	@Override
	public SpeechletResponse onLaunch(final LaunchRequest request, final Session session) throws SpeechletException {
		log.info("onLaunch requestId={}, sessionId={}", request.getRequestId(), session.getSessionId());

		return getWelcomeResponse();
	}

	@Override
	public SpeechletResponse onIntent(final IntentRequest request, final Session session) throws SpeechletException {
		log.info("onIntent requestId={}, sessionId={}", request.getRequestId(), session.getSessionId());

		Intent intent = request.getIntent();
		String intentName = intent.getName();

		if ("SlackIntent".equals(intentName)) {
			return handleOneshotSlackRequest(intent, session);
		} else if ("DialogSlackIntent".equals(intentName)) {
			// Determine if this turn is for city, for date, or an error.
			// We could be passed slots with values, no slots, slots with no
			// value.
			Slot employeeSlot = intent.getSlot(SLOT_EMPLOYEES);
			Slot visitorSlot = intent.getSlot(SLOT_VISITORS);
			if (employeeSlot != null && employeeSlot.getValue() != null) {
				return handleVisitorDialogRequest(intent, session);
			} else if (visitorSlot != null && visitorSlot.getValue() != null) {
				return handleEmployeeDialogRequest(intent, session);
			} else {
				return handleNoSlotDialogRequest(intent, session);
			}
		} else if ("AMAZON.HelpIntent".equals(intentName)) {
			return handleHelpRequest();
		} else if ("AMAZON.StopIntent".equals(intentName)) {
			PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
			outputSpeech.setText("Goodbye");
			return SpeechletResponse.newTellResponse(outputSpeech);
		} else if ("AMAZON.CancelIntent".equals(intentName)) {
			PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
			outputSpeech.setText("Goodbye");
			return SpeechletResponse.newTellResponse(outputSpeech);
		} else {
			throw new SpeechletException("Invalid Intent");
		}
	}

	@Override
	public void onSessionEnded(final SessionEndedRequest request, final Session session) throws SpeechletException {
		log.info("onSessionEnded requestId={}, sessionId={}", request.getRequestId(), session.getSessionId());
	}

	private SpeechletResponse getWelcomeResponse() {
		String speechOutput = "Welcome to Sidebench, who are you here to see? I can send them a message for you";
		String repromptText = "I can help send a message to whoever you're here to see";
		return newAskResponse(speechOutput, false, repromptText, false);
	}

	private SpeechletResponse handleHelpRequest() {
		String repromptText = "Who are you here to see?";
		String speechOutput = "I can send a message to anybody in the office. Just tell me your name and who you're here to see"
				+ " in a form like, I'm Bob here to see Kevin." + repromptText;

		return newAskResponse(speechOutput, repromptText);
	}

	/**
	 * Handles the dialog step where the visitor provides their name
	 */
	private SpeechletResponse handleEmployeeDialogRequest(final Intent intent, final Session session) {
		Slot visitorSlot = intent.getSlot(SLOT_VISITORS);

		// if we don't have an employee, ask for him. If we do, then go to final
		// request
		if (session.getAttributes().containsKey("employee")) {
			String employeeName = (String) session.getAttribute("employee");

			return getFinalResponse(employeeName, visitorSlot.getValue());
		} else {
			// set visitor in session and prompt for employee
			session.setAttribute("visitor", visitorSlot.getValue());
			String speechOutput = "Who are you here to see?";
			return newAskResponse(speechOutput, speechOutput);
		}
	}

	private SpeechletResponse handleVisitorDialogRequest(final Intent intent, final Session session) {
		Slot employeeSlot = intent.getSlot(SLOT_EMPLOYEES);

		// if we don't have visitor's name, ask for it, if we do, make final
		// request.
		if (session.getAttributes().containsKey("visitor")) {
			String visitorName = (String) session.getAttribute("visitor");

			return getFinalResponse(employeeSlot.getValue(), visitorName);
		} else {
			// set employee in session, and prompt for visitor's name
			session.setAttribute("employee", employeeSlot.getValue());
			String speechOutput = "What's your name?";
			return newAskResponse(speechOutput, speechOutput);
		}
	}

	/**
	 * Handle no slots, or slot(s) with no values. In the case of a dialog based
	 * skill with multiple slots, when passed a slot with no value, we cannot
	 * have confidence it is is the correct slot type so we rely on session
	 * state to determine the next turn in the dialog, and reprompt.
	 */
	private SpeechletResponse handleNoSlotDialogRequest(final Intent intent, final Session session) {
		String speechOutput = "Sorry, I didn't understand that, please say your name or who you're here to visit.";
		return newAskResponse(speechOutput, speechOutput);
	}

	/**
	 * This handles the one-shot interaction, where the user utters a phrase
	 * like: 'Alexa, open Tide Pooler and get tide information for Seattle on
	 * Saturday'. If there is an error in a slot, this will guide the user to
	 * the dialog approach.
	 */
	private SpeechletResponse handleOneshotSlackRequest(final Intent intent, final Session session) {
		// Determine city, using default if none provided
		Slot employeeSlot = intent.getSlot(SLOT_EMPLOYEES);
		Slot visitorSlot = intent.getSlot(SLOT_VISITORS);
		if (employeeSlot == null || employeeSlot.getValue() == null) {
			String speechOutput = "Who are you here to see?";
			return newAskResponse(speechOutput, speechOutput);
		} else if (visitorSlot == null || visitorSlot.getValue() == null) {
			// invalid city. move to the dialog
			String speechOutput = "What is your name?";
			// repromptText is the same as the speechOutput
			return newAskResponse(speechOutput, speechOutput);
		}

		// all slots filled, either from the user or by default values. Move to
		// final request
		return getFinalResponse(employeeSlot.getValue(), visitorSlot.getValue());
	}

	/**
	 * Both the one-shot and dialog based paths lead to this method to issue the
	 * request, and respond to the user with the final answer.
	 */
	private SpeechletResponse getFinalResponse(String employee, String visitor) {
		String speechOutput = "Ok, " + visitor + ", I just sent a message to " + employee
				+ ", please have a seat and wait.";
		SimpleCard card = new SimpleCard();
		card.setTitle("SideSlacker");
		card.setContent(speechOutput);

		// Create the plain text output
		PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
		outputSpeech.setText(speechOutput);
		return SpeechletResponse.newTellResponse(outputSpeech, card);
	}

	/**
	 * Uses NOAA.gov API, documented at noaa.gov. Results can be verified at:
	 * http://tidesandcurrents.noaa.gov/noaatidepredictions/NOAATidesFacade.jsp?Stationid=[id]
	 * .
	 *
	 * @see <a href = "http://tidesandcurrents.noaa.gov/api/">noaa.gov</a>
	 * @throws IOException
	 *
	private SpeechletResponse makeTideRequest(slackHandles<String, String> cityStation,
			slackHandles<String, String> date) {
		String queryString = String.format(
				"?%s&station=%s&product=predictions&datum=%s&units=english" + "&time_zone=lst_ldt&format=json",
				date.apiValue, cityStation.apiValue, DATUM);

		String speechOutput = "";

		InputStreamReader inputStream = null;
		BufferedReader bufferedReader = null;
		StringBuilder builder = new StringBuilder();
		try {
			String line;
			URL url = new URL(ENDPOINT + queryString);
			inputStream = new InputStreamReader(url.openStream(), Charset.forName("US-ASCII"));
			bufferedReader = new BufferedReader(inputStream);
			while ((line = bufferedReader.readLine()) != null) {
				builder.append(line);
			}
		} catch (IOException e) {
			// reset builder to a blank string
			builder.setLength(0);
		} finally {
			IOUtils.closeQuietly(inputStream);
			IOUtils.closeQuietly(bufferedReader);
		}

		if (builder.length() == 0) {
			speechOutput = "Sorry, the National Oceanic tide service is experiencing a problem. "
					+ "Please try again later.";
		} else {
			try {
				JSONObject noaaResponseObject = new JSONObject(new JSONTokener(builder.toString()));
				if (noaaResponseObject != null) {
					HighTideValues highTideResponse = findHighTide(noaaResponseObject);
					speechOutput = new StringBuilder().append(date.speechValue).append(" in ")
							.append(cityStation.speechValue).append(", the first high tide will be around ")
							.append(highTideResponse.firstHighTideTime).append(", and will peak at about ")
							.append(highTideResponse.firstHighTideHeight).append(", followed by a low tide at around ")
							.append(highTideResponse.lowTideTime).append(" that will be about ")
							.append(highTideResponse.lowTideHeight).append(". The second high tide will be around ")
							.append(highTideResponse.secondHighTideTime).append(", and will peak at about ")
							.append(highTideResponse.secondHighTideHeight).append(".").toString();
				}
			} catch (JSONException | ParseException e) {
				log.error("Exception occoured while parsing service response.", e);
			}
		}

		// Create the Simple card content.
		SimpleCard card = new SimpleCard();
		card.setTitle("Tide Pooler");
		card.setContent(speechOutput);

		// Create the plain text output
		PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
		outputSpeech.setText(speechOutput);

		return SpeechletResponse.newTellResponse(outputSpeech, card);
	}*/

	/**
	 * Wrapper for creating the Ask response from the input strings with plain
	 * text output and reprompt speeches.
	 *
	 * @param stringOutput
	 *            the output to be spoken
	 * @param repromptText
	 *            the reprompt for if the user doesn't reply or is
	 *            misunderstood.
	 * @return SpeechletResponse the speechlet response
	 */
	private SpeechletResponse newAskResponse(String stringOutput, String repromptText) {
		return newAskResponse(stringOutput, false, repromptText, false);
	}

	/**
	 * Wrapper for creating the Ask response from the input strings.
	 *
	 * @param stringOutput
	 *            the output to be spoken
	 * @param isOutputSsml
	 *            whether the output text is of type SSML
	 * @param repromptText
	 *            the reprompt for if the user doesn't reply or is
	 *            misunderstood.
	 * @param isRepromptSsml
	 *            whether the reprompt text is of type SSML
	 * @return SpeechletResponse the speechlet response
	 */
	private SpeechletResponse newAskResponse(String stringOutput, boolean isOutputSsml, String repromptText,
			boolean isRepromptSsml) {
		OutputSpeech outputSpeech, repromptOutputSpeech;
		if (isOutputSsml) {
			outputSpeech = new SsmlOutputSpeech();
			((SsmlOutputSpeech) outputSpeech).setSsml(stringOutput);
		} else {
			outputSpeech = new PlainTextOutputSpeech();
			((PlainTextOutputSpeech) outputSpeech).setText(stringOutput);
		}

		if (isRepromptSsml) {
			repromptOutputSpeech = new SsmlOutputSpeech();
			((SsmlOutputSpeech) repromptOutputSpeech).setSsml(stringOutput);
		} else {
			repromptOutputSpeech = new PlainTextOutputSpeech();
			((PlainTextOutputSpeech) repromptOutputSpeech).setText(repromptText);
		}

		Reprompt reprompt = new Reprompt();
		reprompt.setOutputSpeech(repromptOutputSpeech);
		return SpeechletResponse.newAskResponse(outputSpeech, reprompt);
	}
}
