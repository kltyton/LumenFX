#if UNITY_EDITOR
using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace LumenFx.UnityExport
{
    internal static class LumenFxJson
    {
        public static string Write(object value)
        {
            StringBuilder builder = new StringBuilder(8192);
            WriteValue(builder, value, 0);
            builder.Append('\n');
            return builder.ToString();
        }

        private static void WriteValue(StringBuilder builder, object value, int depth)
        {
            if (value == null)
            {
                builder.Append("null");
                return;
            }

            string text = value as string;
            if (text != null)
            {
                WriteString(builder, text);
                return;
            }

            if (value is bool)
            {
                builder.Append((bool)value ? "true" : "false");
                return;
            }

            if (value is int || value is long || value is short || value is byte)
            {
                builder.Append(Convert.ToString(value, CultureInfo.InvariantCulture));
                return;
            }

            if (value is float)
            {
                WriteNumber(builder, (float)value);
                return;
            }

            if (value is double)
            {
                WriteNumber(builder, (double)value);
                return;
            }

            IDictionary dictionary = value as IDictionary;
            if (dictionary != null)
            {
                WriteObject(builder, dictionary, depth);
                return;
            }

            IList list = value as IList;
            if (list != null)
            {
                WriteArray(builder, list, depth);
                return;
            }

            throw new InvalidOperationException("Unsupported JSON value type " + value.GetType());
        }

        private static void WriteObject(StringBuilder builder, IDictionary dictionary, int depth)
        {
            builder.Append('{');
            if (dictionary.Count != 0)
            {
                builder.Append('\n');
                int index = 0;
                foreach (DictionaryEntry pair in dictionary)
                {
                    if (!(pair.Key is string key))
                        throw new InvalidOperationException("LumenFX JSON object keys must be strings");
                    Indent(builder, depth + 1);
                    WriteString(builder, key);
                    builder.Append(": ");
                    WriteValue(builder, pair.Value, depth + 1);
                    if (++index != dictionary.Count) builder.Append(',');
                    builder.Append('\n');
                }
                Indent(builder, depth);
            }
            builder.Append('}');
        }

        private static void WriteArray(StringBuilder builder, IList list, int depth)
        {
            builder.Append('[');
            if (list.Count != 0)
            {
                builder.Append('\n');
                for (int i = 0; i < list.Count; i++)
                {
                    Indent(builder, depth + 1);
                    WriteValue(builder, list[i], depth + 1);
                    if (i + 1 != list.Count) builder.Append(',');
                    builder.Append('\n');
                }
                Indent(builder, depth);
            }
            builder.Append(']');
        }

        private static void WriteNumber(StringBuilder builder, double value)
        {
            if (double.IsNaN(value) || double.IsInfinity(value))
                throw new InvalidOperationException("Cannot write a non-finite number to LumenFX JSON");
            builder.Append(value.ToString("R", CultureInfo.InvariantCulture));
        }

        private static void WriteNumber(StringBuilder builder, float value)
        {
            if (float.IsNaN(value) || float.IsInfinity(value))
                throw new InvalidOperationException("Cannot write a non-finite number to LumenFX JSON");
            builder.Append(value.ToString("R", CultureInfo.InvariantCulture));
        }

        private static void WriteString(StringBuilder builder, string value)
        {
            builder.Append('"');
            for (int i = 0; i < value.Length; i++)
            {
                char character = value[i];
                switch (character)
                {
                    case '"': builder.Append("\\\""); break;
                    case '\\': builder.Append("\\\\"); break;
                    case '\b': builder.Append("\\b"); break;
                    case '\f': builder.Append("\\f"); break;
                    case '\n': builder.Append("\\n"); break;
                    case '\r': builder.Append("\\r"); break;
                    case '\t': builder.Append("\\t"); break;
                    default:
                        if (character < 0x20)
                            builder.Append("\\u").Append(((int)character).ToString("x4", CultureInfo.InvariantCulture));
                        else
                            builder.Append(character);
                        break;
                }
            }
            builder.Append('"');
        }

        private static void Indent(StringBuilder builder, int depth)
        {
            builder.Append(' ', depth * 2);
        }

        public static List<Dictionary<string, object>> ReadDocuments(string text)
        {
            var docs = new List<Dictionary<string, object>>();
            if (string.IsNullOrEmpty(text)) return docs;
            var reader = new JsonReader(text);
            while (reader.HasNextToken())
            {
                object value = reader.ReadValue();
                var dict = value as Dictionary<string, object>;
                if (dict != null) docs.Add(dict);
            }
            return docs;
        }

        private sealed class JsonReader
        {
            private readonly string text;
            private int pos;

            public JsonReader(string text)
            {
                this.text = text;
            }

            public bool HasNextToken()
            {
                SkipWhitespace();
                return pos < text.Length;
            }

            public object ReadValue()
            {
                SkipWhitespace();
                if (pos >= text.Length) throw new FormatException("Unexpected end of JSON");
                char ch = text[pos];
                if (ch == '{') return ReadObject();
                if (ch == '[') return ReadArray();
                if (ch == '"') return ReadString();
                if (ch == 't' && Match("true")) return true;
                if (ch == 'f' && Match("false")) return false;
                if (ch == 'n' && Match("null")) return null;
                return ReadNumber();
            }

            private Dictionary<string, object> ReadObject()
            {
                var result = new Dictionary<string, object>(StringComparer.Ordinal);
                pos++;
                SkipWhitespace();
                if (pos < text.Length && text[pos] == '}') { pos++; return result; }
                while (pos < text.Length)
                {
                    SkipWhitespace();
                    string key = ReadString();
                    SkipWhitespace();
                    if (pos >= text.Length || text[pos] != ':') throw new FormatException("Expected ':' in JSON object");
                    pos++;
                    result[key] = ReadValue();
                    SkipWhitespace();
                    if (pos < text.Length && text[pos] == ',') { pos++; continue; }
                    if (pos < text.Length && text[pos] == '}') { pos++; return result; }
                    throw new FormatException("Expected ',' or '}' in JSON object");
                }
                throw new FormatException("Unterminated JSON object");
            }

            private List<object> ReadArray()
            {
                var result = new List<object>();
                pos++;
                SkipWhitespace();
                if (pos < text.Length && text[pos] == ']') { pos++; return result; }
                while (pos < text.Length)
                {
                    result.Add(ReadValue());
                    SkipWhitespace();
                    if (pos < text.Length && text[pos] == ',') { pos++; continue; }
                    if (pos < text.Length && text[pos] == ']') { pos++; return result; }
                    throw new FormatException("Expected ',' or ']' in JSON array");
                }
                throw new FormatException("Unterminated JSON array");
            }

            private string ReadString()
            {
                if (text[pos] != '"') throw new FormatException("Expected '\"' in JSON string");
                pos++;
                var sb = new StringBuilder();
                while (pos < text.Length)
                {
                    char c = text[pos++];
                    if (c == '"') return sb.ToString();
                    if (c != '\\') { sb.Append(c); continue; }
                    if (pos >= text.Length) throw new FormatException("Unterminated escape");
                    char esc = text[pos++];
                    switch (esc)
                    {
                        case '"': sb.Append('"'); break;
                        case '\\': sb.Append('\\'); break;
                        case '/': sb.Append('/'); break;
                        case 'b': sb.Append('\b'); break;
                        case 'f': sb.Append('\f'); break;
                        case 'n': sb.Append('\n'); break;
                        case 'r': sb.Append('\r'); break;
                        case 't': sb.Append('\t'); break;
                        case 'u':
                            if (pos + 4 > text.Length) throw new FormatException("Invalid \\u escape");
                            sb.Append((char)Convert.ToInt32(text.Substring(pos, 4), 16));
                            pos += 4;
                            break;
                        default: sb.Append(esc); break;
                    }
                }
                throw new FormatException("Unterminated JSON string");
            }

            private double ReadNumber()
            {
                int start = pos;
                if (text[pos] == '-') pos++;
                while (pos < text.Length && (char.IsDigit(text[pos]) || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-'))
                    pos++;
                return double.Parse(text.Substring(start, pos - start), CultureInfo.InvariantCulture);
            }

            private bool Match(string literal)
            {
                if (pos + literal.Length > text.Length) return false;
                if (string.CompareOrdinal(text, pos, literal, 0, literal.Length) != 0) return false;
                pos += literal.Length;
                return true;
            }

            private void SkipWhitespace()
            {
                while (pos < text.Length && char.IsWhiteSpace(text[pos])) pos++;
            }
        }
    }
}
#endif
