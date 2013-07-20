package biweekly.io.text;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

import biweekly.ICalException;
import biweekly.parameter.ICalParameters;

/*
 Copyright (c) 2013, Michael Angstadt
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met: 

 1. Redistributions of source code must retain the above copyright notice, this
 list of conditions and the following disclaimer. 
 2. Redistributions in binary form must reproduce the above copyright notice,
 this list of conditions and the following disclaimer in the documentation
 and/or other materials provided with the distribution. 

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * Parses an iCalendar data stream.
 * @author Michael Angstadt
 * @see <a href="http://tools.ietf.org/html/rfc5545">RFC 5545</a>
 */
public class ICalRawReader implements Closeable {
	private static final String NEWLINE = System.getProperty("line.separator");
	private final FoldedLineReader reader;
	private boolean caretDecodingEnabled = true;
	private boolean eof = false;

	/**
	 * Creates a new reader.
	 * @param reader the reader to the data stream
	 */
	public ICalRawReader(Reader reader) {
		this.reader = new FoldedLineReader(reader);
	}

	/**
	 * Gets the line number of the last line that was read.
	 * @return the line number
	 */
	public int getLineNum() {
		return reader.getLineNum();
	}

	/**
	 * Starts or continues reading from the iCalendar data stream.
	 * @param listener handles the iCalendar data as it is read off the wire
	 * @throws IOException if there is an I/O problem
	 */
	public void start(ICalDataStreamListener listener) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			try {
				parseLine(line, listener);
			} catch (StopReadingException e) {
				return;
			}
		}
		eof = true;
	}

	private void parseLine(String line, ICalDataStreamListener listener) {
		String propertyName = null;
		ICalParameters parameters = new ICalParameters();
		String value = null;

		char escapeChar = 0; //is the next char escaped?
		boolean inQuotes = false; //are we inside of double quotes?
		StringBuilder buffer = new StringBuilder();
		String curParamName = null;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (escapeChar != 0) {
				if (escapeChar == '\\') {
					//backslash escaping in parameter values is not part of the standard
					if (ch == '\\') {
						buffer.append(ch);
					} else if (ch == 'n' || ch == 'N') {
						//newlines
						buffer.append(NEWLINE);
					} else if (ch == '"') {
						//incase a double quote is escaped with a backslash
						buffer.append(ch);
					} else {
						//treat the escape character as a normal character because it's not a valid escape sequence
						buffer.append(escapeChar).append(ch);
					}
				} else if (escapeChar == '^') {
					if (ch == '^') {
						buffer.append(ch);
					} else if (ch == 'n') {
						buffer.append(NEWLINE);
					} else if (ch == '\'') {
						buffer.append('"');
					} else {
						//treat the escape character as a normal character because it's not a valid escape sequence
						buffer.append(escapeChar).append(ch);
					}
				}
				escapeChar = 0;
			} else if (ch == '\\' || (ch == '^' && caretDecodingEnabled)) {
				escapeChar = ch;
			} else if ((ch == ';' || ch == ':') && !inQuotes) {
				if (propertyName == null) {
					propertyName = buffer.toString();
				} else if (curParamName == null) {
					//value-less parameter (bad iCal syntax)
					String parameterName = buffer.toString();
					listener.valuelessParameter(propertyName, parameterName);
					parameters.put(parameterName, null);
				} else {
					//parameter value
					String paramValue = buffer.toString();
					parameters.put(curParamName, paramValue);
					curParamName = null;
				}
				buffer.setLength(0);

				if (ch == ':') {
					if (i < line.length() - 1) {
						value = line.substring(i + 1);
					} else {
						value = "";
					}
					break;
				}
			} else if (ch == ',' && !inQuotes) {
				//multi-valued parameter
				parameters.put(curParamName, buffer.toString());
				buffer.setLength(0);
			} else if (ch == '=' && curParamName == null) {
				//parameter name
				curParamName = buffer.toString();
				buffer.setLength(0);
			} else if (ch == '"') {
				inQuotes = !inQuotes;
			} else {
				buffer.append(ch);
			}
		}

		if (propertyName == null || value == null) {
			listener.invalidLine(line);
			return;
		}
		if ("BEGIN".equalsIgnoreCase(propertyName)) {
			listener.beginComponent(value);
			return;
		}
		if ("END".equalsIgnoreCase(propertyName)) {
			listener.endComponent(value);
			return;
		}
		listener.readProperty(propertyName, parameters, value);
	}

	/**
	 * <p>
	 * Gets whether the reader will decode parameter values that use circumflex
	 * accent encoding (enabled by default). This escaping mechanism allows
	 * newlines and double quotes to be included in parameter values.
	 * </p>
	 * 
	 * <table border="1">
	 * <tr>
	 * <th>Raw Character</th>
	 * <th>Encoded Character</th>
	 * </tr>
	 * <tr>
	 * <td><code>"</code></td>
	 * <td><code>^'</code></td>
	 * </tr>
	 * <tr>
	 * <td><i>newline</i></td>
	 * <td><code>^n</code></td>
	 * </tr>
	 * <tr>
	 * <td><code>^</code></td>
	 * <td><code>^^</code></td>
	 * </tr>
	 * </table>
	 * 
	 * <p>
	 * Example:
	 * </p>
	 * 
	 * <pre>
	 * GEO;X-ADDRESS="Pittsburgh Pirates^n115 Federal St^nPitt
	 *  sburgh, PA 15212":40.446816;80.00566
	 * </pre>
	 * 
	 * @return true if circumflex accent decoding is enabled, false if not
	 * @see <a href="http://tools.ietf.org/html/rfc6868">RFC 6868</a>
	 */
	public boolean isCaretDecodingEnabled() {
		return caretDecodingEnabled;
	}

	/**
	 * <p>
	 * Sets whether the reader will decode parameter values that use circumflex
	 * accent encoding (enabled by default). This escaping mechanism allows
	 * newlines and double quotes to be included in parameter values.
	 * </p>
	 * 
	 * <table border="1">
	 * <tr>
	 * <th>Raw Character</th>
	 * <th>Encoded Character</th>
	 * </tr>
	 * <tr>
	 * <td><code>"</code></td>
	 * <td><code>^'</code></td>
	 * </tr>
	 * <tr>
	 * <td><i>newline</i></td>
	 * <td><code>^n</code></td>
	 * </tr>
	 * <tr>
	 * <td><code>^</code></td>
	 * <td><code>^^</code></td>
	 * </tr>
	 * </table>
	 * 
	 * <p>
	 * Example:
	 * </p>
	 * 
	 * <pre>
	 * GEO;X-ADDRESS="Pittsburgh Pirates^n115 Federal St^nPitt
	 *  sburgh, PA 15212":geo:40.446816,-80.00566
	 * </pre>
	 * 
	 * @param enable true to use circumflex accent decoding, false not to
	 * @see <a href="http://tools.ietf.org/html/rfc6868">RFC 6868</a>
	 */
	public void setCaretDecodingEnabled(boolean enable) {
		caretDecodingEnabled = enable;
	}

	/**
	 * Determines whether the end of the data stream has been reached.
	 * @return true if the end has been reached, false if not
	 */
	public boolean eof() {
		return eof;
	}

	/**
	 * Handles the iCalendar data as it is read off the data stream. Each one of
	 * this interface's methods may throw a {@link StopReadingException} at any
	 * time to force the parser to stop reading from the data stream. This will
	 * cause the reader to return from the {@link ICalRawReader#start} method.
	 * To continue reading from the data stream, simply call the
	 * {@link ICalRawReader#start} method again.
	 * @author Michael Angstadt
	 */
	public static interface ICalDataStreamListener {
		/**
		 * Called when a component begins (when a "BEGIN:NAME" property is
		 * reached).
		 * @param name the component name (e.g. "VEVENT")
		 * @throws StopReadingException to force the reader to stop reading from
		 * the data stream
		 */
		void beginComponent(String name);

		/**
		 * Called when a property is read.
		 * @param name the property name (e.g. "VERSION")
		 * @param parameters the parameters
		 * @param value the property value
		 * @throws StopReadingException to force the reader to stop reading from
		 * the data stream
		 */
		void readProperty(String name, ICalParameters parameters, String value);

		/**
		 * Called when a component ends (when a "END:NAME" property is reached).
		 * @param name the component name (e.g. "VEVENT")
		 * @throws StopReadingException to force the reader to stop reading from
		 * the data stream
		 */
		void endComponent(String name);

		/**
		 * Called when a line cannot be parsed.
		 * @param line the unparseable line
		 * @throws StopReadingException to force the reader to stop reading from
		 * the data stream
		 */
		void invalidLine(String line);

		/**
		 * Called when a value-less parameter is read.
		 * @param propertyName the property name (e.g. "VERSION")
		 * @param parameterName the parameter name (e.g. "FMTTYPE")
		 */
		void valuelessParameter(String propertyName, String parameterName);
	}

	/**
	 * Instructs an {@link ICalRawReader} to stop reading from the data stream
	 * when thrown from an {@link ICalDataStreamListener} implementation.
	 * @author Michael Angstadt
	 */
	@SuppressWarnings("serial")
	public static class StopReadingException extends ICalException {
		//empty
	}

	/**
	 * Closes the underlying {@link Reader} object.
	 */
	public void close() throws IOException {
		reader.close();
	}
}
