function Callout(el)
  if quarto.doc.isFormat("html") then
    if not el.collapse then
      el.collapse = true
    end
    return el
  end
end
